package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.Stash
import scala.collection.immutable
import scala.concurrent.duration._
import spray.contrib.socketio
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.NoopPacket
import spray.contrib.socketio.packet.Packet

object ConnectionActive {
  case object ConnectedTime

  case object Pause
  case object Awake

  final case class SendPackets(packets: Seq[Packet])
  final case class WriteMultiple(serverConnection: ActorRef)
  final case class WriteSingle(serverConnection: ActorRef, isSendingNoopWhenEmpty: Boolean = false)

}

/**
 *
 * connectionActive <1--1> connContext <1--n> transport <1--1..n> serverConnection
 */
class ConnectionActive(connContext: ConnectionContext, namespaces: ActorRef) extends Actor with Stash with ActorLogging {
  import ConnectionActive._
  import context.dispatcher

  val startTime = System.currentTimeMillis

  def heartbeatInterval = socketio.Settings.HeartbeatTimeout * 0.618

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatHandler: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  var pendingPackets = immutable.Queue[Packet]()

  def receive = sending orElse paused

  def paused: Receive = {
    case Awake =>
      log.debug("{}: awaked.", self)
      heartbeatHandler = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds) {
        connContext.transport.sendPacket(HeartbeatPacket)
      })
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(
        socketio.Settings.HeartbeatTimeout.seconds,
        namespaces, Namespace.HeartbeatTimeout(connContext.sessionId)))
      //unstashAll()
      context.become(sending orElse working)

    case msg =>
    //stash()
  }

  def working: Receive = {
    case Pause =>
      log.debug("{}: paused.", self)
      heartbeatHandler foreach (_.cancel)
      context.become(sending orElse paused)

    case HeartbeatPacket =>
      heartbeatTimeout foreach (_.cancel)
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(
        socketio.Settings.HeartbeatTimeout.seconds,
        namespaces, Namespace.HeartbeatTimeout(connContext.sessionId)))

    case ConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

  }

  def sending: Receive = {
    case SendPackets(packets)                                  => sendPacket(packets)
    case WriteMultiple(serverConnection)                       => writeMultiple(serverConnection)
    case WriteSingle(serverConnection, isSendingNoopWhenEmpty) => writeSingle(serverConnection, isSendingNoopWhenEmpty)
  }

  private def sendPacket(packets: Seq[Packet]) {
    packets foreach { packet => pendingPackets = pendingPackets.enqueue(packet) }
    log.debug("Enqueued {}, pendingPackets: {}", packets, pendingPackets)
  }

  /**
   * It seems XHR-Pollong client does not support multile packets.
   */
  private def writeMultiple(serverConnection: ActorRef) {
    if (pendingPackets.isEmpty) {
      // do nothing
    } else if (pendingPackets.tail.isEmpty) {
      val head = pendingPackets.head
      pendingPackets = pendingPackets.tail
      val payload = head.render.utf8String
      log.debug("Writing {}, to {}", payload, serverConnection)
      connContext.transport.write(serverConnection, payload)
    } else {
      var totalLength = 0
      val sb = new StringBuilder()
      var prev: Packet = null
      while (pendingPackets.nonEmpty) {
        val curr = pendingPackets.head
        curr match {
          case NoopPacket | HeartbeatPacket if curr == prev => // keep one is enough
          case _ =>
            val msg = curr.render.utf8String
            totalLength += msg.length
            sb.append('\ufffd').append(msg.length.toString).append('\ufffd').append(msg)
        }
        pendingPackets = pendingPackets.tail
        prev = curr
      }
      val payload = sb.toString
      log.debug("Writing {}, to {}", payload, serverConnection)
      connContext.transport.write(serverConnection, payload)
    }
  }

  private def writeSingle(serverConnection: ActorRef, isSendingNoopWhenEmpty: Boolean) {
    if (pendingPackets.isEmpty) {
      if (isSendingNoopWhenEmpty) {
        connContext.transport.write(serverConnection, NoopPacket.utf8String)
      }
    } else {
      val head = pendingPackets.head
      pendingPackets = pendingPackets.tail
      val payload = head.render.utf8String
      log.debug("Dispatching {}, to {}", payload, serverConnection)
      connContext.transport.write(serverConnection, payload)
    }
  }
}

