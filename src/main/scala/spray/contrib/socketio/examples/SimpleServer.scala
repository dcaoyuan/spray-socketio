package spray.contrib.socketio.examples

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import rx.lang.scala.Observer
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.LocalConnectionActiveResolver
import spray.contrib.socketio.SocketIOServerConnection
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.namespace.LocalNamespace
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.http.{ HttpMethods, Uri, HttpEntity, ContentType, MediaTypes }
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.json.DefaultJsonProtocol

object SimpleServer extends App with MySslConfiguration {

  class SocketIOServer(resolver: ActorRef) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, resolver))
        serverConnection ! Http.Register(conn)
    }
  }

  val WEB_ROOT = "/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples"

  class SocketIOWorker(val serverConnection: ActorRef, val resolver: ActorRef) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case HttpRequest(HttpMethods.GET, Uri.Path("/socketio.html"), _, _, _) =>
        val content = renderTextFile(WEB_ROOT + "/socketio.html")
        val entity = HttpEntity(ContentType(MediaTypes.`text/html`), content)
        sender() ! HttpResponse(entity = entity)

      case HttpRequest(HttpMethods.GET, Uri.Path("/jquery-1.7.2.min.js"), _, _, _) =>
        val content = renderTextFile(WEB_ROOT + "/jquery-1.7.2.min.js")
        val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
        sender() ! HttpResponse(entity = entity)

      case HttpRequest(HttpMethods.GET, Uri.Path("/socket.io.js"), _, _, _) =>
        val content = renderTextFile(WEB_ROOT + "/socket.io.js")
        val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
        sender() ! HttpResponse(entity = entity)

      case x: HttpRequest =>
        log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)

      case x: Frame =>
    }

    def renderTextFile(path: String) = {
      val source = scala.io.Source.fromFile(path)
      val lines = source.getLines mkString "\n"
      source.close()
      lines
    }
  }

  // --- json protocals for socketio messages:
  case class Msg(message: String)
  case class Now(time: String)
  object TheJsonProtocol extends DefaultJsonProtocol {
    implicit val msgFormat = jsonFormat1(Msg)
    implicit val nowFormat = jsonFormat1(Now)
  }
  import spray.json._
  import TheJsonProtocol._

  implicit val system = ActorSystem()
  implicit val resolver = LocalConnectionActiveResolver(system)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("Hi!", args, context) =>
          println("observed: " + next.name + ", " + next.args)
          next.replyEvent("welcome", List(Msg("Greeting from spray-socketio")).toJson.toString)
          next.replyEvent("time", List(Now((new java.util.Date).toString)).toJson.toString)
          // batched packets
          next.reply(
            EventPacket(-1L, false, "testendpoint", "welcome", List(Msg("Batcher Greeting from spray-socketio")).toJson.toString),
            EventPacket(-1L, false, "testendpoint", "time", List(Now("Batched " + (new java.util.Date).toString)).toJson.toString))
        case OnEvent("time", args, context) =>
          println("observed: " + next.name + ", " + next.args)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  Namespace.subscribe("testendpoint", observer)(system, Props(classOf[LocalNamespace], "testendpoint"))
  val server = system.actorOf(Props(classOf[SocketIOServer], resolver), name = "socketio-server")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
