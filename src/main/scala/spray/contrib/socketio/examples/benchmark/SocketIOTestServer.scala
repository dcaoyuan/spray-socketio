package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import rx.lang.scala.Observer
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.Frame
import spray.contrib.socketio
import spray.contrib.socketio.Namespace
import spray.contrib.socketio.Namespace.OnEvent
import spray.contrib.socketio.SocketIOServerConnection
import com.typesafe.config.ConfigFactory

object SocketIOTestServer extends App {

  class SocketIOServer(namespaces: ActorRef) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, namespaces))
        serverConnection ! Http.Register(conn)
    }
  }

  class SocketIOWorker(val serverConnection: ActorRef, val namespaces: ActorRef) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  val namespaces = system.actorOf(Props(classOf[Namespace.Namespaces]).withDispatcher(socketio.Settings.NamespacesDispatcher), name = Namespace.NAMESPACES)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          next.replyEvent("chat", args: _*)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })
  namespaces ! Namespace.Subscribe("", observer)

  import system.dispatcher

  val server = system.actorOf(Props(classOf[SocketIOServer], namespaces), "socketio")
  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")

  IO(UHttp) ! Http.Bind(server, host, port)
}
