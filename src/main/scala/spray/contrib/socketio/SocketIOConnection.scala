package spray.contrib.socketio

import akka.actor.ActorRef
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.transport.Transport
import spray.json.JsValue

final case class SocketIOContext(transport: Transport, sessionId: String)

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
final case class SocketIOConnection(endpoint: String, sender: ActorRef, context: SocketIOContext) {

  def sessionId = context.sessionId

  def sendMessage(message: String) {
    val packet = MessagePacket(-1L, false, endpoint, message)
    send(packet)
  }

  def sendJson(json: JsValue) {
    val packet = JsonPacket(-1L, false, endpoint, json)
    send(packet)
  }

  def send(packet: Packet) {
    context.transport.send(packet, sender)
  }

  def sendEvent(name: String, args: List[JsValue]) {
    val packet = EventPacket(-1L, false, endpoint, name, args)
    send(packet)
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
