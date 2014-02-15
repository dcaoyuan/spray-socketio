package spray.contrib.socketio.transport

import akka.actor.ActorRef
import java.net.InetSocketAddress
import java.util.UUID
import spray.contrib.socketio.namespace.AckCallback
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet

class SocketIOClient(
  val namespace: String,
  val transport: Transport,
  val sessionId: String,
  val remoteAddress: InetSocketAddress) {

  private def ackIdOf(ackCallback: AckCallback[_]): Long = {
    //baseClient.ackManager.registerAck(sessionId, ackCallback)
    -1L
  }

  def sendEvent(name: String, data: String, ackCallback: Option[AckCallback[_]] = None) {
    val packet = ackCallback match {
      case Some(x) => EventPacket(ackIdOf(x), true, namespace, data)
      case None    => EventPacket(-1L, false, namespace, data)
    }
    send(packet, ackCallback)
  }

  def sendMessage(message: String, ackCallback: Option[AckCallback[_]] = None) {
    val packet = ackCallback match {
      case Some(x) => MessagePacket(ackIdOf(x), true, namespace, message)
      case None    => MessagePacket(-1L, false, namespace, message)
    }
    send(packet, ackCallback)
  }

  def sendJsonObject(obj: Object, ackCallback: Option[AckCallback[_]] = None) {
    val json = obj.toString // TODO
    val packet = ackCallback match {
      case Some(x) => JsonPacket(ackIdOf(x), true, namespace, json)
      case None    => JsonPacket(-1L, false, namespace, json)
    }
    send(packet, ackCallback)
  }

  def send(packet: Packet, ackCallback: Option[AckCallback[_]]) {
    //if (ackCallback.resultClass != classOf[Void]) {
    //  packet.ack = Packet.ACK_DATA
    //}
    send(packet)
  }

  def send(packet: Packet) {
    //clientActor ! packet
  }

  def onDisconnect() {
    //namespace.onDisconnect(this)
    //clientActor.removeChildClient(this);
  }

  def disconnect() {
    send(DisconnectPacket(namespace))
    onDisconnect()
  }

}
