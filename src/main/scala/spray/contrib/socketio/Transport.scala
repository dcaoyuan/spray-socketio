package spray.contrib.socketio

import akka.actor.ActorRef
import akka.util.ByteString
import spray.can.Http
import spray.can.websocket.FrameCommand
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.packet.Packet
import spray.http.ContentType
import spray.http.HttpEntity
import spray.http.HttpHeaders
import spray.http.HttpOrigin
import spray.http.HttpResponse
import spray.http.MediaTypes
import spray.http.SomeOrigins

object Transport {
  val idToTransport = Set(
    XhrPolling,
    XhrMultipart,
    HtmlFile,
    WebSocket,
    FlashSocket,
    JsonpPolling).map(x => x.id -> x).toMap

  def transportFor(id: String): Option[Transport] = idToTransport.get(id)

  def isSupported(id: String) = idToTransport.contains(id)
}

trait Transport {
  def id: String
  def send(serverConnection: ActorRef, packets: List[Packet])(origins: Seq[HttpOrigin])

  override def toString = id
}

object XhrPolling extends Transport {
  def id = "xhr-polling"

  def send(serverConnection: ActorRef, packets: List[Packet])(origins: Seq[HttpOrigin]) {
    val originsHeaders = List(
      HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
      HttpHeaders.`Access-Control-Allow-Credentials`(true))

    val headers = List(HttpHeaders.Connection("keep-alive")) ::: originsHeaders
    val payload = Packet.renderToString(packets)
    serverConnection ! Http.MessageCommand(HttpResponse(headers = headers, entity = HttpEntity(ContentType(MediaTypes.`text/plain`), payload)))
  }
}

object XhrMultipart extends Transport {
  def id = "xhr-multipart"

  def send(serverConnection: ActorRef, packets: List[Packet])(origins: Seq[HttpOrigin]) {
    // TODO
  }
}

object HtmlFile extends Transport {
  def id = "htmlfile"

  def send(serverConnection: ActorRef, packets: List[Packet])(origins: Seq[HttpOrigin]) {
    // TODO
  }
}

object WebSocket extends Transport {
  def id = "websocket"

  def send(serverConnection: ActorRef, packets: List[Packet])(origins: Seq[HttpOrigin]) {
    val payload = ByteString(Packet.renderToString(packets))
    serverConnection ! FrameCommand(TextFrame(payload))
  }
}

object FlashSocket extends Transport {
  def id = "flashsocket"

  def send(serverConnection: ActorRef, packets: List[Packet])(origins: Seq[HttpOrigin]) {
    // TODO
  }
}

object JsonpPolling extends Transport {
  def id = "jsonp-polling"

  def send(serverConnection: ActorRef, packets: List[Packet])(origins: Seq[HttpOrigin]) {
    // TODO
  }
}