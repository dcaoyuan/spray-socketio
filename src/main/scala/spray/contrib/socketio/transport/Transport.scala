package spray.contrib.socketio.transport

import akka.actor.ActorRef
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketRender

object Transport {
  val idToTransport = Set(
    XhrPolling,
    XhrMultipart,
    HtmlFile,
    WebSocket,
    FlashSocket,
    JsonpPolling).map(x => x.id -> x).toMap

  def transportFor(id: String): Option[Transport] = idToTransport.get(id)
}

trait Transport {
  def id: String
  def send(packet: Packet, client: ActorRef)
}

object XhrPolling extends Transport {
  def id = "xhr-polling"

  def send(packet: Packet, client: ActorRef) {
    // TODO
  }
}

object XhrMultipart extends Transport {
  def id = "xhr-multipart"

  def send(packet: Packet, client: ActorRef) {
    // TODO
  }
}

object HtmlFile extends Transport {
  def id = "htmlfile"

  def send(packet: Packet, client: ActorRef) {
    // TODO
  }
}

object WebSocket extends Transport {
  def id = "websocket"

  def send(packet: Packet, client: ActorRef) {
    client ! TextFrame(PacketRender.render(packet))
  }
}

object FlashSocket extends Transport {
  def id = "flashsocket"

  def send(packet: Packet, client: ActorRef) {
    // TODO
  }
}

object JsonpPolling extends Transport {
  def id = "jsonp-polling"

  def send(packet: Packet, client: ActorRef) {
    // TODO
  }
}