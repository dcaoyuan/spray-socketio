spray-socketio
==============

socket.io for spray

Example (in progressing):

```scala

package spray.contrib.socketio.examples

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging }
import akka.pattern._
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
import spray.http.{ HttpHeaders, HttpMethods, HttpRequest, Uri, HttpResponse, HttpEntity }
import org.parboiled2.ParseError
import rx.lang.scala.Observer
import scala.concurrent.duration._
import HttpHeaders._
import HttpMethods._

object SimpleServer extends App with MySslConfiguration {
  implicit val system = ActorSystem()

  class SocketIOServer extends Actor with ActorLogging {

    def receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        log.info("Connected to HttpListener, sender {}", sender().path)
        sender() ! Http.Register(self)

      // socket.io handshake
      case socketio.HandshakeRequest(resp) =>
        log.info("socketio handshake from sender is {}", sender().path)
        sender() ! resp

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case req @ websocket.HandshakeRequest(state) =>
        state match {
          case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
          case wsContext: websocket.HandshakeContext =>
            log.info("websocker handshaked from sender {}", sender().path)
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
        socketio.soContextFor(wsContext.uri, sender()) match {
          case Some(soContext) => namespaces ! Namespace.Connected(soContext)
          case None            =>
        }
        log.info("Http Upgraded!")

      case x @ TextFrame(payload) =>
        try {
          val packets = PacketParser(payload)
          log.info("got {}, from sender {}", packets, sender().path)
          packets foreach { namespaces ! Namespace.OnPacket(_, sender()) }
        } catch {
          case ex: ParseError => log.error(ex, "Error in parsing packet: {}" + ex.getMessage)
        }

      case x: Frame =>
      //log.info("Got frame: {}", x)

      case HttpRequest(GET, Uri.Path("/pingpingping"), _, _, _) =>
        sender() ! HttpResponse(entity = "PONG!PONG!PONG!")

      case x: HttpRequest =>
        log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)
        log.info(x.toString)

    }
  }

  import system.dispatcher

  val namespaces = system.actorOf(Props[Namespace.Namespaces], name = "namespaces")
  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      println("observed: " + next.name + ", " + next.args)
      next.replyEvent("welcome", Nil)
    })
  namespaces ! Namespace.Subscribe("testendpoint", observer)

  val worker = system.actorOf(Props(classOf[SocketIOServer]), "websocket")

  IO(UHttp) ! Http.Bind(worker, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}

```