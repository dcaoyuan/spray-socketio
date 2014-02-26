package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.Stash
import scala.concurrent.duration._
import spray.contrib.socketio
import spray.contrib.socketio.packet.HeartbeatPacket

object ConnectionActive {
  case object ConnectedTime

  case object Pause
  case object Awake
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
  var heartbeatFiring: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  def receive = paused

  def paused: Receive = {
    case Awake =>
      log.debug("{}: awaked.", self)
      heartbeatFiring = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds) {
        connContext.transport.sendPacket(HeartbeatPacket)
      })
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce((socketio.Settings.HeartbeatTimeout).seconds) {
        namespaces ! Namespace.HeartbeatTimeout(connContext.sessionId)
      })

      //unstashAll()
      context.become(processing)

    case msg =>
    //stash()
  }

  def processing: Receive = {
    case Pause =>
      log.debug("{}: paused.", self)
      heartbeatFiring foreach (_.cancel)
      context.become(paused)

    case HeartbeatPacket =>
      heartbeatTimeout foreach (_.cancel)
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce((socketio.Settings.HeartbeatTimeout).seconds) {
        namespaces ! Namespace.HeartbeatTimeout(connContext.sessionId)
      })

    case ConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

  }
}

