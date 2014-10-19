package spray.contrib.socketio.namespace

import akka.actor.Props
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage
import scala.annotation.tailrec
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
  private var buf = Vector.empty[OnPacket[_]]

  def receive = {
    case x: OnPacket[_] =>
      if (buf.isEmpty && totalDemand > 0) {
        onPacket(x)
      } else {
        buf :+= x
        deliverBuf()
      }
    case ActorPublisherMessage.Request(_) =>
      deliverBuf()
    case ActorPublisherMessage.Cancel =>
      context.stop(self)
  }

  @tailrec
  final def deliverBuf(): Unit =
    if (totalDemand > 0) {
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buf.splitAt(totalDemand.toInt)
        buf = keep
        use foreach onPacket
      } else {
        val (use, keep) = buf.splitAt(Int.MaxValue)
        buf = keep
        use foreach onPacket
        deliverBuf()
      }
    }

  final def onPacket(x: OnPacket[_]) = x.packet match {
    case packet: ConnectPacket    => onNext(OnConnect(packet.args, x.connContext)(packet))
    case packet: DisconnectPacket => onNext(OnDisconnect(x.connContext)(packet))
    case packet: MessagePacket    => onNext(OnMessage(packet.data, x.connContext)(packet))
    case packet: JsonPacket       => onNext(OnJson(packet.json, x.connContext)(packet))
    case packet: EventPacket      => onNext(OnEvent(packet.name, packet.args, x.connContext)(packet))
  }
}

