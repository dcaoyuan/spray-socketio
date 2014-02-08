package spray.contrib.socketio.examples

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging }
import akka.pattern._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.packet.PacketRender
import spray.contrib.socketio.transport.WebSocket
import spray.http.{ HttpHeaders, HttpMethods, HttpRequest, Uri, HttpResponse, StatusCodes, SomeOrigins }
import scala.concurrent.duration._
import HttpHeaders._
import HttpMethods._

object SimpleServer extends App with MySslConfiguration {

  class SocketioServer extends Actor with ActorLogging {
    private var isSocketioOnWebSocket = false

    def receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        log.info("Connected to HttpListener: {}", sender().path)
        sender() ! Http.Register(self)

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case req @ websocket.HandshakeRequest(state) =>
        state match {
          case x: websocket.HandshakeFailure => sender() ! x.response
          case x: websocket.HandshakeSuccess => sender() ! UHttp.Upgrade(websocket.pipelineStage(self, x), Some(x.response))
        }

        socketio.transportFor(req) match {
          case Some(WebSocket) =>
            log.info("seems a socket.io connecting.")
            isSocketioOnWebSocket = true // will response a ConnectPacket later
          case _ =>
        }

      // upgraded successfully
      case UHttp.Upgraded =>
        log.info("Http Upgraded!")
        if (isSocketioOnWebSocket) {
          sender() ! TextFrame(PacketRender.render(ConnectPacket()))
        }

      // socket.io handshake
      case socketio.HandshakeRequest(resp) =>
        //log.info("socket.io handshake, path:{}, query:{}", uri.path, uri.query)
        sender() ! resp

      case x @ TextFrame(payload) =>
        val packets = PacketParser(payload)
        log.info("got {}", packets)
        sender() ! x

      case x: Frame =>
      //log.info("Got frame: {}", x)

      // --- test code for socket.io etc

      case HttpRequest(GET, Uri.Path("/pingpingping"), _, _, _) =>
        sender() ! HttpResponse(entity = "PONG!PONG!PONG!")

      case x: HttpRequest =>
        log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)
        log.info(x.toString)

    }
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val worker = system.actorOf(Props(classOf[SocketioServer]), "websocket")

  IO(UHttp) ! Http.Bind(worker, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()

}
