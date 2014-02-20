spray-socketio
==============

socket.io for spray

Example (in progressing):

```scala

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
import spray.contrib.socketio.packet.PacketParser
import spray.http.{ HttpMethods, HttpRequest, Uri, HttpResponse, HttpEntity }
import spray.json.DefaultJsonProtocol

object SimpleServer extends App with MySslConfiguration {

  /**
   * This is actually a singleton actor due to akka IO machanism, we can only
   * identify each client-connection by underlying sender()
   */
  class SocketIOServer(namespaces: ActorRef) extends Actor with ActorLogging {

    def receive = socketIOLogic orElse genericLogic

    def socketIOLogic: Receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        sender() ! Http.Register(self)

      // socket.io handshake
      case socketio.HandshakeRequest(state) =>
        namespaces ! Namespace.Session(state.sessionId)
        sender() ! state.response

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case req @ websocket.HandshakeRequest(state) =>
        state match {
          case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
          case wsContext: websocket.HandshakeContext =>
            val newContext = if (socketio.isSocketIOConnecting(req.uri)) {
              val connectPacket = FrameRender.render(TextFrame(ConnectPacket().render))
              wsContext.withResponse(wsContext.response.withEntity(HttpEntity(connectPacket.toArray)))
            } else {
              wsContext
            }
            sender() ! UHttp.Upgrade(websocket.pipelineStage(self, newContext), newContext)
        }

      // upgraded successfully
      case UHttp.Upgraded(wsContext) =>
        socketio.contextFor(wsContext.uri, sender()) match {
          case Some(soContext) => namespaces ! Namespace.Connected(soContext)
          case None            =>
        }
        log.debug("Http Upgraded!")

      case x @ TextFrame(payload) if Namespace.isSocketIOConnection(sender()) =>
        try {
          val packets = PacketParser(payload)
          log.debug("got {}, from sender {}", packets, sender().path)
          packets foreach { namespaces ! Namespace.OnPacket(_, sender()) }
        } catch {
          case ex: ParseError => log.error(ex, "Error in parsing packet: {}" + ex.getMessage)
        }

      case x: HttpRequest if Namespace.isSocketIOConnection(sender()) =>
    }

    def genericLogic: Receive = {
      case x: Frame =>
      //log.info("Got frame: {}", x)

      case HttpRequest(HttpMethods.GET, Uri.Path("/pingpingping"), _, _, _) =>
        sender() ! HttpResponse(entity = "PONG!PONG!PONG!")

      case x: HttpRequest =>
        log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)
        log.info(x.toString)

    }
  }

  // --- json protocals:
  case class Msg(message: String)
  object TheJsonProtocol extends DefaultJsonProtocol {
    implicit val msgFormat = jsonFormat1(Msg)
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
          next.replyEvent("welcome", List(Msg("Greeting from spray-socketio").toJson))
          next.replyEvent("time", List(Msg((new java.util.Date).toString).toJson))
        case OnEvent("time", args, context) =>
          println("observed: " + next.name + ", " + next.args)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })
  namespaces ! Namespace.Subscribe("testendpoint", observer)

  import system.dispatcher

  val worker = system.actorOf(Props(classOf[SocketIOServer], namespaces), "websocket")

  IO(UHttp) ! Http.Bind(worker, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}

```