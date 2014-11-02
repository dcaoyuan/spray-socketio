package spray.contrib.socketio.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.io.IO
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.WatermarkRequestStrategy
import scala.concurrent.Future
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.ConnectionSession.OnEvent
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.SocketIOServerWorker
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.namespace.Queue
import spray.http.{ HttpMethods, Uri, HttpEntity, ContentType, MediaTypes }
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.json.DefaultJsonProtocol

object SimpleServer extends App with MySslConfiguration {

  object SocketIOServer {
    def props() = Props(classOf[SocketIOServer])
  }
  class SocketIOServer() extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(SocketIOWorker.props(serverConnection))
        serverConnection ! Http.Register(conn)
    }
  }

  val WEB_ROOT = "/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples"

  object SocketIOWorker {
    def props(serverConnection: ActorRef) = Props(classOf[SocketIOWorker], serverConnection)
  }
  class SocketIOWorker(val serverConnection: ActorRef) extends Actor with SocketIOServerWorker {
    def sessionRegion = SocketIOExtension(context.system).sessionRegion

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

  class Receiver extends ActorSubscriber {
    implicit def sessionClient = socketioExt.sessionClient

    override val requestStrategy = WatermarkRequestStrategy(10)

    def receive = {
      case OnNext(event @ OnEvent("Hi!", args, context)) =>
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

      case OnNext(OnEvent("time", args, context)) =>
        println("observed: " + "time" + ", " + args)

      case OnNext(value) =>
        println("observed: " + value)
    }
  }

  val queue = system.actorOf(Queue.props(), "myqueue")
  val receiver = system.actorOf(Props(new Receiver), "myreceiver")
  ActorPublisher(queue).subscribe(ActorSubscriber(receiver))
  // there is no channel.ofType method for RxScala, why?
  //queue.flatMap {
  //  case x: OnEvent => Observable.items(x)
  //  case _          => Observable.empty
  //}.subscribe(observer)

  socketioExt.namespaceClient ! Subscribe("testendpoint", None, queue)

  val server = system.actorOf(SocketIOServer.props(), name = "socketio-server")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
