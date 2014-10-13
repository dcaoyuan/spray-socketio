package spray.contrib.socketio.namespace

import akka.actor.Props
import akka.stream.actor.ActorPublisher
import spray.contrib.socketio.ConnectionSession.OnPacket
import spray.contrib.socketio.namespace.Namespace.OnConnect
import spray.contrib.socketio.namespace.Namespace.OnData
import spray.contrib.socketio.namespace.Namespace.OnDisconnect
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.namespace.Namespace.OnJson
import spray.contrib.socketio.namespace.Namespace.OnMessage
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket

object Channel {
  def props() = Props(classOf[Channel])
}

class Channel extends ActorPublisher[OnData] {
  def receive = {
    case OnPacket(packet: ConnectPacket, connContext)    => onNext(OnConnect(packet.args, connContext)(packet))
    case OnPacket(packet: DisconnectPacket, connContext) => onNext(OnDisconnect(connContext)(packet))
    case OnPacket(packet: MessagePacket, connContext)    => onNext(OnMessage(packet.data, connContext)(packet))
    case OnPacket(packet: JsonPacket, connContext)       => onNext(OnJson(packet.json, connContext)(packet))
    case OnPacket(packet: EventPacket, connContext)      => onNext(OnEvent(packet.name, packet.args, connContext)(packet))
  }
}

