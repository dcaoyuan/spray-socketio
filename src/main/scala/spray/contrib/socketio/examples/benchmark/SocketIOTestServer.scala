package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import rx.lang.scala.Observer
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.ConnectionActiveResolver
import spray.contrib.socketio.GeneralConnectionActiveResolver
import spray.contrib.socketio.GeneralNamespace
import spray.contrib.socketio.Namespace
import spray.contrib.socketio.Namespace.OnEvent
import spray.contrib.socketio.SocketIOServerConnection
import com.typesafe.config.ConfigFactory
import spray.contrib.socketio.Namespace.OnEvent

object SocketIOTestServer extends App {

  class SocketIOServer(val resolver: ConnectionActiveResolver) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, resolver))
        serverConnection ! Http.Register(conn)
    }
  }

  class SocketIOWorker(val serverConnection: ActorRef, val resolver: ConnectionActiveResolver) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  implicit val resolver = GeneralConnectionActiveResolver(system)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          spray.json.JsonParser(args) // test spray-json performance too.
          next.replyEvent("chat", args)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  Namespace.subscribe(Namespace.DEFAULT_NAMESPACE, observer)(system, Props(classOf[GeneralNamespace], Namespace.DEFAULT_NAMESPACE))
  val server = system.actorOf(Props(classOf[SocketIOServer], resolver), name = "socketio")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  IO(UHttp) ! Http.Bind(server, host, port)
}
