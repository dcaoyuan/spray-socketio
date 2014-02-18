package spray.contrib.socketio

import akka.actor.ActorRef
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.transport.Transport
import spray.json.JsValue

/**
 * Socket.IO has built-in support for multiple channels of communication
 * (which we call "multiple sockets"). Each socket is identified by an endpoint
 * (can be omitted).
 *
 * During connecting handshake (1::), endpoint is "", the default endpoint.
 * The client may then send ConnectPacket with endpoint (1::/endp1) and
 * (1::/endp2) etc to use the same sender-context pair as multiple sockets.
 * @See Namespace
 */
final case class SocketIOConnection(transport: Transport, sessionId: String, sender: ActorRef) {

  def sendMessage(message: String)(implicit endpoint: String) {
    val packet = MessagePacket(-1L, false, endpoint, message)
    send(packet)
  }

  def sendJson(json: JsValue)(implicit endpoint: String) {
    val packet = JsonPacket(-1L, false, endpoint, json)
    send(packet)
  }

  def sendEvent(name: String, args: List[JsValue])(implicit endpoint: String) {
    val packet = EventPacket(-1L, false, endpoint, name, args)
    send(packet)
  }

  def send(packet: Packet)(implicit endpoint: String) {
    transport.send(packet, sender)
  }

  def onDisconnect() {
    //namespace.onDisconnect(this)
    //clientActor.removeChildClient(this);
  }

  //  def disconnect() {
  //    send(DisconnectPacket(namespace))
  //    onDisconnect()
  //  }
}
