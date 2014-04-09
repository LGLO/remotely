package remotely.server

import akka.actor.{Actor,ActorLogging,ActorRef,ActorSystem,Props}
import akka.io.{BackpressureBuffer,IO,Tcp,SslTlsSupport,TcpPipelineHandler}
import akka.kernel.Bootable
import akka.util.ByteString
import java.net.InetSocketAddress
import javax.net.ssl.SSLEngine
import scalaz.concurrent.Task
import scalaz.stream.{async,Process}

/**
 * Create a server on the given `InetSockeAddress`, using `handler` for processing
 * each request, and using `ssl` to optionally
 */
class HandlerServer(handler: Handler, addr: InetSocketAddress, ssl: Option[() => SSLEngine] = None) extends Actor with ActorLogging {

  import context.system

  override def preStart = {
    log.debug("server attempting to bind to: " + addr)
    IO(Tcp) ! Tcp.Bind(self, addr)
  }

  override def postStop = {
    log.info("server shut down")
  }

  def receive = {
    case b @ Tcp.Bound(localAddress) =>
      log.info("server bound to: " + localAddress)
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.error("server failed to bind to: " + addr + ", shutting down")
      context stop self
    case Tcp.Connected(remote, _) =>
      log.debug("connection established")
      val connection = sender
      val pipeline = ssl.map { engine =>
        val sslEngine = engine()
        val init = TcpPipelineHandler.withLogger(log,
          new SslTlsSupport(sslEngine) >>
          new BackpressureBuffer(lowBytes = 128, highBytes = 1024 * 16, maxBytes = 4096 * 1000 * 100))
        lazy val sslConnection: ActorRef =
          context.actorOf(TcpPipelineHandler.props(
            init,
            connection,
            handler.actor(context.system)(sslConnection))) // tie the knot, give the handler actor a reference to
                                                           // overall connection actor, which does SSL
        sslConnection
      } getOrElse { handler.actor(context.system)(connection) }
      connection ! Tcp.Register(pipeline, keepOpenOnPeerClosed = true)
  }
}

