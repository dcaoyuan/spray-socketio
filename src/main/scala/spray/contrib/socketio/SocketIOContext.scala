package spray.contrib.socketio

import akka.actor.ActorRef
import spray.contrib.socketio.SocketIOConnection.SendEvent
import spray.contrib.socketio.SocketIOConnection.SendJson
import spray.contrib.socketio.SocketIOConnection.SendMessage
import spray.contrib.socketio.SocketIOConnection.SendPacket
import spray.contrib.socketio.packet.Packet
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
 *
 * @Note let this context not to be final, so business application can store more
 * states in it.
 */
class SocketIOContext(val transport: Transport, val sessionId: String, private var _transportActor: ActorRef) {

  def transportActor = _transportActor
  def withTransportActor(transportActor: ActorRef) {
    _transportActor = transportActor
  }

  private var _conn: ActorRef = _
  def conn = _conn
  def withConnection(conn: ActorRef) {
    _conn = conn
  }

  private[socketio] def sendMessage(msg: String)(implicit endpoint: String) {
    conn ! SendMessage(msg)
  }

  private[socketio] def sendJson(json: JsValue)(implicit endpoint: String) {
    conn ! SendJson(json)
  }

  private[socketio] def sendEvent(name: String, args: List[JsValue])(implicit endpoint: String) {
    conn ! SendEvent(name, args)
  }

  private[socketio] def send(packet: Packet) {
    conn ! SendPacket(packet)
  }

  private[socketio] def onDisconnect() {
    //namespace.onDisconnect(this)
    //clientActor.removeChildClient(this);
  }

  //  def disconnect() {
  //    send(DisconnectPacket(namespace))
  //    onDisconnect()
  //  }
  //

}
