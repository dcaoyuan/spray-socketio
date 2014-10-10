package spray.contrib.socketio.examples

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import rx.lang.scala.Observable
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.concurrent.Future
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.SocketIOServerWorker
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.{ OnData, OnEvent }
import spray.contrib.socketio.namespace.NamespaceExtension
import spray.http.{ HttpMethods, Uri, HttpEntity, ContentType, MediaTypes }
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.json.DefaultJsonProtocol

object SimpleServer extends App with MySslConfiguration {

  object SocketIOServer {
    def props(sessionRegion: ActorRef) = Props(classOf[SocketIOServer], sessionRegion)
  }
  class SocketIOServer(sessionRegion: ActorRef) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(SocketIOWorker.props(serverConnection, sessionRegion))
        serverConnection ! Http.Register(conn)
    }
  }

  val WEB_ROOT = "/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples"

  object SocketIOWorker {
    def props(serverConnection: ActorRef, sessionRegion: ActorRef) = Props(classOf[SocketIOWorker], serverConnection, sessionRegion)
  }
  class SocketIOWorker(val serverConnection: ActorRef, val sessionRegion: ActorRef) extends Actor with SocketIOServerWorker {

    override def sessionIdGenerator: HttpRequest => Future[String] = { req =>
      Future.successful("123456")
    }

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

  // --- json protocols for socketio messages:
  case class Msg(message: String)
  case class Now(time: String)
  object TheJsonProtocol extends DefaultJsonProtocol {
    implicit val msgFormat = jsonFormat1(Msg)
    implicit val nowFormat = jsonFormat1(Now)
  }
  import spray.json._
  import TheJsonProtocol._

  implicit val system = ActorSystem()
  val socketioExt = SocketIOExtension(system)
  val namespaceExt = NamespaceExtension(system)
  implicit val sessionRegion = namespaceExt.sessionRegion

  val observer = new Observer[OnEvent] {
    override def onNext(value: OnEvent) {
      value match {
        case event @ OnEvent("Hi!", args, context) =>
          println("observed: " + "Hi!" + ", " + args)
          if (event.packet.hasAckData) {
            event.ack("[]")
          }
          event.replyEvent("welcome", List(Msg("Greeting from spray-socketio")).toJson.toString)
          event.replyEvent("time", List(Now((new java.util.Date).toString)).toJson.toString)
          // batched packets
          event.reply(
            EventPacket(-1L, false, "testendpoint", "welcome", List(Msg("Batcher Greeting from spray-socketio")).toJson.toString),
            EventPacket(-1L, false, "testendpoint", "time", List(Now("Batched " + (new java.util.Date).toString)).toJson.toString))
        case OnEvent("time", args, context) =>
          println("observed: " + "time" + ", " + args)
        case _ =>
          println("observed: " + value)
      }
    }
  }

  val channel = Subject[OnData]()
  // there is no channel.ofType method for RxScala, why?
  channel.flatMap {
    case x: OnEvent => Observable.items(x)
    case _          => Observable.empty
  }.subscribe(observer)

  namespaceExt.startNamespace("testendpoint")
  namespaceExt.namespace("testendpoint") ! Namespace.Subscribe(channel)

  val server = system.actorOf(SocketIOServer.props(sessionRegion), name = "socketio-server")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
