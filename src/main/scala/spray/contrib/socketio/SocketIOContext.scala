package spray.contrib.socketio

import akka.actor.ActorRef
import spray.contrib.socketio.ConnectionActive.ProcessingWith
import spray.contrib.socketio.ConnectionActive.SendEvent
import spray.contrib.socketio.ConnectionActive.SendJson
import spray.contrib.socketio.ConnectionActive.SendMessage
import spray.contrib.socketio.ConnectionActive.SendPackets
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
 *
 * serverConnection <--> SocketIOContext <--> connectionActive
 */
class SocketIOContext(val transport: Transport, val sessionId: String, val serverConnection: ActorRef) {

  private var _connectionActive: ActorRef = _
  def connectionActive = _connectionActive
  def withConnectionActive(connectionActive: ActorRef) = {
    _connectionActive = connectionActive
    _connectionActive ! ProcessingWith(this)
    this
  }

  private[socketio] def sendMessage(msg: String, endpoint: String) {
    connectionActive ! SendMessage(msg, endpoint)
  }

  private[socketio] def sendJson(json: JsValue, endpoint: String) {
    connectionActive ! SendJson(json, endpoint)
  }

  private[socketio] def sendEvent(name: String, args: List[JsValue], endpoint: String) {
    connectionActive ! SendEvent(name, args, endpoint)
  }

  private[socketio] def send(packets: List[Packet]) {
    connectionActive ! SendPackets(packets)
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
