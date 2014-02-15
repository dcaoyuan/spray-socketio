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
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnPacket
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.packet.PacketRender
import spray.http.{ HttpHeaders, HttpMethods, HttpRequest, Uri, HttpResponse, HttpEntity }
import rx.lang.scala.Observer
import scala.concurrent.duration._
import HttpHeaders._
import HttpMethods._

object SimpleServer extends App with MySslConfiguration {
  implicit val system = ActorSystem()

  class SocketioServer extends Actor with ActorLogging {

    def receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        log.info("Connected to HttpListener")
        sender() ! Http.Register(self)

      // socket.io handshake
      case socketio.HandshakeRequest(resp) =>
        log.info("socketio handshake")
        sender() ! resp

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case req @ websocket.HandshakeRequest(state) =>
        state match {
          case x: websocket.HandshakeFailure => sender() ! x.response
          case x: websocket.HandshakeSuccess =>
            val connectPacket = FrameRender.render(TextFrame(PacketRender.render(ConnectPacket())))
            val resp = x.response.withEntity(HttpEntity(connectPacket.toArray))
            sender() ! UHttp.Upgrade(websocket.pipelineStage(self, x), Some(resp))
        }

        log.info("websocker handshaked")
        socketio.clientFor(req) match {
          case Some(client) =>
          case _            =>
        }

      // upgraded successfully
      case UHttp.Upgraded =>
        log.info("Http Upgraded!")

      case x @ TextFrame(payload) =>
        val packets = PacketParser(payload)
        log.info("got {}", packets)
        packets foreach { namespaces ! Namespace.OnPacket(_, sender()) }
      //sender() ! x

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
  val observer = Observer[OnPacket[EventPacket]](
    (next: OnPacket[EventPacket]) => { println("observed: " + next.packet.data) },
    (error: Throwable) => {},
    () => {})
  namespaces ! Namespace.Subscribe("testendpoint", observer)

  val worker = system.actorOf(Props(classOf[SocketioServer]), "websocket")

  IO(UHttp) ! Http.Bind(worker, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
