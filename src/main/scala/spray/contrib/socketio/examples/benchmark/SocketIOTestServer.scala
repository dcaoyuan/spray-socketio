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
import spray.json.DefaultJsonProtocol

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

  // --- json protocals for socketio messages:
  case class Msg(test: String)
  case class Now(time: String)
  object TheJsonProtocol extends DefaultJsonProtocol {
    implicit val msgFormat = jsonFormat1(Msg)
    implicit val nowFormat = jsonFormat1(Now)
  }
  import spray.json._
  import TheJsonProtocol._

  implicit val system = ActorSystem()
  val namespaces = system.actorOf(Props(classOf[Namespace.Namespaces]).withDispatcher(socketio.Settings.NamespacesDispatcher), name = Namespace.NAMESPACES)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          //println("observed: " + next.name + ", " + next.args)
          next.replyEvent("chat", args: _*)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })
  namespaces ! Namespace.Subscribe("", observer)

  import system.dispatcher

  val server = system.actorOf(Props(classOf[SocketIOServer], namespaces), "socketio")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
