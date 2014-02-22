package spray.contrib.socketio.examples

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import akka.pattern._
import org.parboiled2.ParseError
import rx.lang.scala.Observer
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.FrameRender
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.Namespace
import spray.contrib.socketio.Namespace.OnEvent
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.PacketParser
import spray.http.HttpHeaders.Origin
import spray.http.{ HttpMethods, HttpRequest, Uri, HttpResponse, HttpEntity, HttpHeaders, ContentTypes, ContentType, MediaTypes, SomeOrigins, HttpProtocols, HttpOrigin }
import spray.json.DefaultJsonProtocol

object SimpleServer extends App with MySslConfiguration {

  class SocketIOServer(namespaces: ActorRef) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOConnection], serverConnection, namespaces))
        serverConnection ! Http.Register(conn)
    }
  }

  class SocketIOConnection(serverConnection: ActorRef, namespaces: ActorRef) extends Actor with ActorLogging {

    def receive = socketioHandshake orElse websocketConnecting orElse xrhpollingConnecting orElse genericLogic orElse closeLogic

    def closeLogic: Receive = {
      case x: Http.ConnectionClosed =>
        context.stop(self)
        log.debug("Connection closed due to {}, {}: stopped.", x, self)
    }

    def socketioHandshake: Receive = {
      case socketio.HandshakeRequest(state) =>
        log.debug("{}: socketio handshake", self)
        namespaces ! Namespace.Session(state.sessionId)
        sender() ! state.response
    }

    def websocketConnecting: Receive = {
      case req @ websocket.HandshakeRequest(state) =>
        state match {
          case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
          case wsContext: websocket.HandshakeContext =>
            val theContext = socketio.contextFor(wsContext.request, serverConnection) match {
              case Some(soContext) =>
                log.info("{}: websocket handshake successed.", self)
                namespaces ! Namespace.Connecting(soContext)
                val connectPacket = FrameRender.render(TextFrame(ConnectPacket().render))
                wsContext.withResponse(wsContext.response.withEntity(HttpEntity(connectPacket.toArray)))
              case None =>
                wsContext
            }
            sender() ! UHttp.Upgrade(websocket.pipelineStage(self, theContext), theContext)
        }

      // upgraded successfully
      case UHttp.Upgraded(wsContext) =>
        log.debug("{}: upgraded.", self)

      case TextFrame(payload) if Namespace.isSocketIOConnected(serverConnection) =>
        try {
          val packets = PacketParser(payload)
          log.debug("got {}, self {}", packets, self)
          packets foreach { namespaces ! Namespace.OnPacket(_, serverConnection) }
        } catch {
          case ex: ParseError => log.error(ex, "Error in parsing packet: {}" + ex.getMessage)
        }
    }

    def xrhpollingConnecting: Receive = {
      case req @ HttpRequest(HttpMethods.GET, uri, _, _, HttpProtocols.`HTTP/1.1`) if socketio.isSocketIOConnecting(uri) & !Namespace.isSocketIOConnected(serverConnection) =>
        log.info("conn {}, sender {}: socketio GET {}", self, sender(), req.entity)
        socketio.contextFor(req, serverConnection) match {
          case Some(soContext) =>
            namespaces ! Namespace.Connecting(soContext)

            val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
            val originsHeaders = if (origins.nonEmpty) {
              List(
                HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
                HttpHeaders.`Access-Control-Allow-Credentials`(true))
            } else {
              Nil
            }
            val headers = List(HttpHeaders.Connection("keep-alive")) ::: originsHeaders
          //sendActor ! SendMsg(sender(), ConnectPacket(), origins)
          //httpSend(ConnectPacket(), sender(), origins)

          case None =>
        }

      case req @ HttpRequest(HttpMethods.POST, uri, _, entity, HttpProtocols.`HTTP/1.1`) if Namespace.isSocketIOConnected(serverConnection) =>
        log.info("conn {}, sender {}: socketio POST {}", self, sender(), req.entity)
        val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
        try {
          val packets = PacketParser(entity.data.toByteString)
          log.info("Got: {}", packets)
          packets foreach { namespaces ! Namespace.OnPacket(_, serverConnection) }
          //packets foreach { x => httpSend(x, sender(), origins) }
          //sendActor ! SendMsgs(sender(), packets.toList ::: List(welcomePacket), origins)
          //sendActor ! SendMsg(sender(), welcomePacket, origins)
        } catch {
          case ex: ParseError => log.error(ex, "Error in parsing packet: {}" + ex.getMessage)
        }
    }

    def genericLogic: Receive = {
      case HttpRequest(HttpMethods.GET, Uri.Path("/socketio.html"), _, _, _) =>
        val content = renderTextFile("/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples/socketio.html")
        val entity = HttpEntity(ContentType(MediaTypes.`text/html`), content)
        sender() ! HttpResponse(entity = entity)

      case HttpRequest(HttpMethods.GET, Uri.Path("/jquery-1.7.2.min.js"), _, _, _) =>
        val content = renderTextFile("/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples/jquery-1.7.2.min.js")
        val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
        sender() ! HttpResponse(entity = entity)

      case HttpRequest(HttpMethods.GET, Uri.Path("/socket.io.js"), _, _, _) =>
        val content = renderTextFile("/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples/socket.io.js")
        val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
        sender() ! HttpResponse(entity = entity)

      case x: HttpRequest =>
        log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)

      case x: Frame =>
    }

  }

  def renderTextFile(path: String) = {
    val source = scala.io.Source.fromFile(path)
    val lines = source.getLines mkString "\n"
    source.close()
    lines
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
  val namespaces = system.actorOf(Props[Namespace.Namespaces], name = Namespace.NAMESPACES)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("Hi!", args, context) =>
          println("observed: " + next.name + ", " + next.args)
          next.replyEvent("welcome", Msg("Greeting from spray-socketio").toJson)
          next.replyEvent("time", Now((new java.util.Date).toString).toJson)
        case OnEvent("time", args, context) =>
          println("observed: " + next.name + ", " + next.args)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })
  namespaces ! Namespace.Subscribe("testendpoint", observer)

  val welcomePacket = EventPacket(-1, false, "testendpoint", "welcome", List(Msg("Greeting from spray-socketio").toJson))

  import system.dispatcher

  val server = system.actorOf(Props(classOf[SocketIOServer], namespaces), "socketio")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
