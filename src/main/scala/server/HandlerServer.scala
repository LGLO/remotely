package srpc.server

import akka.actor.{Actor,ActorLogging,ActorSystem,PoisonPill,Props}
import akka.io.{IO,Tcp}
import akka.kernel.Bootable
import akka.util.ByteString
import java.net.InetSocketAddress
import scalaz.concurrent.Task
import scalaz.stream.{async,Process}

class HandlerServer(handler: Handler, addr: InetSocketAddress) extends Actor with ActorLogging {

  import context.system

  override def preStart = {
    log.info("server attempting to bind to: " + addr)
    IO(Tcp) ! Tcp.Bind(self, addr)
  }

  override def postStop = {
    log.info("server shut down")
  }

  def receive = {
    case b @ Tcp.Bound(localAddress) =>
      log.info("server bound to: " + localAddress)
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.info("server failed to bind to: " + addr + ", shutting down")
      context stop self
    case connected: Tcp.Connected =>
      log.info("connection established")
      val connection = sender
      connection ! Tcp.Register(handler.actor(context.system)(connection), true)
  }
}

object EchoServer extends App {
  val stop = start("echo-server")(Handler.id, new InetSocketAddress("localhost", 8080))
  readLine()
  stop()
}
