package spray.contrib.socketio

import akka.actor.ActorRef
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.transport.Transport
import spray.json.JsObject
import spray.json.JsValue

case class SocketIOContext(transport: Transport, sessionId: String)

/**
 * Socket.IO has built-in support for multiple channels of communication
 * (which we call "multiple sockets"). Each socket is identified by an endpoint
 * (can be omitted).
 */
case class SocketIOSocket(endpoint: String, sender: ActorRef, context: SocketIOContext) {

  def sendEvent(name: String, data: JsObject) {
    val packet = EventPacket(-1L, false, endpoint, data)
    send(packet)
  }

  def sendMessage(message: String) {
    val packet = MessagePacket(-1L, false, endpoint, message)
    send(packet)
  }

  def sendJsonObject(obj: JsValue) {
    val packet = JsonPacket(-1L, false, endpoint, obj)
    send(packet)
  }

  def send(packet: Packet) {
    context.transport.send(packet, sender)
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
