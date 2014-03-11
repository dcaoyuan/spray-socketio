package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import rx.lang.scala.Observer
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio._
import com.typesafe.config.ConfigFactory
import spray.contrib.socketio.Namespace.OnEvent

object SocketIOTestServer extends App {

  class SocketIOServer(val selection: ConnectionActiveSelector) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, selection))
        serverConnection ! Http.Register(conn)
    }
  }

  class SocketIOWorker(val serverConnection: ActorRef, val selector: ConnectionActiveSelector) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  implicit val selection = new GeneralConnectionActiveSelector(system)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          next.replyEvent("chat", args)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  Namespace.subscribe(Namespace.DEFAULT_NAMESPACE, observer)(system, Props(classOf[GeneralNamespace], Namespace.DEFAULT_NAMESPACE))
  val server = system.actorOf(Props(classOf[SocketIOServer], selection), "socketio")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  IO(UHttp) ! Http.Bind(server, host, port)
}
