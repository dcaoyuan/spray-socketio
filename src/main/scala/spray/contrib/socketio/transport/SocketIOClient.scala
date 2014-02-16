package spray.contrib.socketio.transport

import akka.actor.ActorRef
import java.net.InetSocketAddress
import java.util.UUID
import rx.lang.scala.Observer
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.json.JsObject
import spray.json.JsValue

class SocketIOClient(
  val namespace: String,
  val transport: Transport,
  val sessionId: String,
  val remoteAddress: InetSocketAddress) {

  def sendEvent(name: String, data: JsObject)(implicit client: ActorRef) {
    val packet = EventPacket(-1L, false, namespace, data)
    send(packet)(client)
  }

  def sendMessage(message: String)(implicit client: ActorRef) {
    val packet = MessagePacket(-1L, false, namespace, message)
    send(packet)(client)
  }

  def sendJsonObject(obj: JsValue)(implicit client: ActorRef) {
    val packet = JsonPacket(-1L, false, namespace, obj)
    send(packet)(client)
  }

  def send(packet: Packet)(implicit client: ActorRef) {
    transport.send(packet, client)
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
