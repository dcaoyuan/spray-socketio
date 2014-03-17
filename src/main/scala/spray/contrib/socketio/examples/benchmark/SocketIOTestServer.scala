package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import com.typesafe.config.ConfigFactory
import rx.lang.scala.Observer
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.SocketIOServerConnection
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.extension.SocketIOExtension

object SocketIOTestServer extends App {

  class SocketIOServer(val resolver: ActorRef) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, resolver))
        serverConnection ! Http.Register(conn)
    }
  }

  class SocketIOWorker(val serverConnection: ActorRef, val resolver: ActorRef) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  val socketioExt = SocketIOExtension(system)
  implicit val resolver = SocketIOExtension(system).resolver

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          spray.json.JsonParser(args) // test spray-json performance too.
          if (isBroadcast) {
            next.broadcast("", EventPacket(-1L, false, "", "chat", args))
          } else {
            next.replyEvent("chat", args)
          }
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  socketioExt.startNamespace()
  Namespace.subscribe(observer)(socketioExt.namespace())

  val server = system.actorOf(Props(classOf[SocketIOServer], resolver), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  val isBroadcast = config.getBoolean("broadcast")
  IO(UHttp) ! Http.Bind(server, host, port)
}
