package srpc

import akka.actor.{Actor,ActorLogging,ActorSystem,Props}
import akka.io.{BackpressureBuffer,IO,Tcp,SslTlsSupport,TcpPipelineHandler}
import akka.util.ByteString
import java.net.{InetSocketAddress,Socket,URL}
import javax.net.ssl.SSLEngine
import scalaz.concurrent.Task
import scalaz.stream.{async,Bytes,Channel,Exchange,io,Process,nio}
import scodec.bits.{BitVector,ByteVector}
import Endpoint.Connection

/**
 * A 'logical' endpoint for some service, represented
 * by a possibly rotating stream of `Connection`s.
 */
case class Endpoint(connections: Process[Task,Connection]) {
  def get: Task[Connection] = connections.once.runLast.flatMap {
    case None => Task.fail(new Exception("No available connections"))
    case Some(a) => Task.now(a)
  }
}

object Endpoint {

  type Connection = Process[Task,ByteVector] => Process[Task,ByteVector]

  // combinators for building up Endpoints

  def empty: Endpoint = Endpoint(Process.halt)

  def single(host: InetSocketAddress)(implicit S: ActorSystem): Endpoint =
    Endpoint(Process.constant(akkaRequest(S)(host, None)))

  def singleSSL(createEngine: InetSocketAddress => SSLEngine)(
                host: InetSocketAddress)(implicit S: ActorSystem): Endpoint =
    Endpoint(Process.constant(akkaRequest(S)(host, Some(createEngine))))

    // Endpoint(connect(host).map(streamExchange).repeat)

  private def akkaRequest(system: ActorSystem)(
      host: InetSocketAddress,
      createEngine: Option[InetSocketAddress => SSLEngine] = None): Connection = out => {
    val (q, src) = async.localQueue[ByteVector]
    val actor = system.actorOf(Props(new Actor with ActorLogging {
      import context.system

      override def preStart() =
        IO(Tcp)(context.system) ! Tcp.Connect(host)

      def receive = {
        case Tcp.CommandFailed(_: Tcp.Connect) ⇒
          log.error("connection failed to " + host)
          q.fail(new Exception("connection failed to host " + host))
          context stop self
        case c @ Tcp.Connected(remote, local) =>
          log.debug("connection established to " + remote)
          val connection = sender
          val core = context.system.actorOf(Props(new Actor with ActorLogging { def receive = {
            case Tcp.Received(data) => q.enqueue(ByteVector(data.toArray))
            case Tcp.Aborted => q.fail(new Exception("connection aborted"))
            case Tcp.ErrorClosed(msg) => q.fail(new Exception("I/O error: " + msg))
            case _ : Tcp.ConnectionClosed => q.close; context stop self
          }}))
          val pipeline = createEngine.map { makeSslEngine =>
            val init = TcpPipelineHandler.withLogger(log,
              new SslTlsSupport(makeSslEngine(remote)) >>
              new BackpressureBuffer(lowBytes = 128, highBytes = 1024 * 16, maxBytes = 4096 * 1000 * 100))
            context.actorOf(TcpPipelineHandler.props(init, connection, core))
          } getOrElse { core }
          connection ! Tcp.Register(pipeline)
          // NB: the client does not close the connection; the server closes the
          // connection when it is finished writing (or in the event of an error)
          out.evalMap { bytes => Task.delay { connection ! Tcp.Write(ByteString(bytes.toArray)) } }
          .run.runAsync { e => e.leftMap(q.fail); context stop self }
      }
    }))
    src
  }

  /**
   * Send a stream of bytes to a server and get back a
   * stream of bytes, allowing for nondeterminism in the
   * rate of processing. (That is, we never block on awaiting
   * confirmation of sending bytes to the server.) Note that
   * this does not do an SSL handshake or encryption.
   */
  private def request(host: InetSocketAddress)(
                      bytes: Process[Task,ByteVector]): Process[Task,ByteVector] =
    nio.connect(host).flatMap { exch =>
      streamExchange {
        exch.mapO(bs => ByteVector.view(bs.toArray))
            .mapW[ByteVector](bs => Bytes.of(bs.toArray))
      } (bytes)
    }

  def streamExchange(exch: Exchange[ByteVector,ByteVector]): Process[Task,ByteVector] => Process[Task,ByteVector] =
    bytes => bytes.to(exch.write).drain ++ exch.read

  def connect(host: InetSocketAddress): Process[Task, Exchange[ByteVector, ByteVector]] =
    Process.eval(Task.delay(new Socket(host.getAddress, host.getPort))).map { socket =>
      val in = socket.getInputStream
      val read  = forked(Process.constant(4096))
                . through (io.chunkR(in))
                . map (ByteVector.view(_))
                . onComplete { Process.eval(Task.delay(socket.shutdownInput)).attempt().drain }
      val write = forked(io.chunkW(socket.getOutputStream).contramap[ByteVector](_.toArray))
                  . map { a => println("socket closed: " + socket.isClosed); a }
                  . onComplete { Process.eval(Task.delay(socket.shutdownOutput)).attempt().drain }
      Exchange[ByteVector,ByteVector](read, write)
    }

  def forked[A](p: Process[Task,A]): Process[Task,A] = p.evalMap(a => Task(a))

  def roundRobin(p: Endpoint*): Endpoint = {
    require(p.nonEmpty, "round robin must have at least one endpoint to choose from")
    val pts = p.toIndexedSeq
    reduceBalanced(pts)(_ => 1)((a,b) => Endpoint(a.connections.interleave(b.connections)))
  }

  private def reduceBalanced[A](v: TraversableOnce[A])(size: A => Int)(
                      f: (A,A) => A): A = {
    @annotation.tailrec
    def fixup(stack: List[(A,Int)]): List[(A,Int)] = stack match {
      // h actually appeared first in `v`, followed by `h2`, preserve this order
      case (h2,n) :: (h,m) :: t if n > m/2 =>
        fixup { (f(h, h2), m+n) :: t }
      case _ => stack
    }
    v.foldLeft(List[(A,Int)]())((stack,a) => fixup((a -> size(a)) :: stack))
     .reverse.map(_._1)
     .reduceLeft(f)
  }

  def logical(name: String)(resolve: String => Endpoint): Endpoint =
    resolve(name)

  // loadBalanced
  // circuitBroken
  // when a connection fails, it is removed from the pool
  // until the next tick of schedule; when pool is empty
  // we fail fast
  // def circuitBroken(schedule: Process[Task,Unit])(
  // healthy: Channel[Task, InetSocketAddress, Boolean])(
  // addr: Endpoint): Endpoint = ???
}
