package spray.contrib.socketio.cluster

import akka.actor.{ActorLogging, ActorRef, ActorSystem}
import akka.contrib.pattern.{ DistributedPubSubMediator, DistributedPubSubExtension, ShardRegion, ClusterSharding }
import akka.persistence.{PersistenceFailure, EventsourcedProcessor}
import spray.contrib.socketio
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.{Namespace, ConnectionContext, ConnectionActive}

object ClusterConnectionActive {
  lazy val idExtractor: ShardRegion.IdExtractor = {
    case cmd: socketio.ConnectionActive.Command => (cmd.sessionId, cmd)
  }

  lazy val shardResolver: ShardRegion.ShardResolver = {
    case cmd: socketio.ConnectionActive.Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
class ClusterConnectionActive extends ConnectionActive with EventsourcedProcessor with ActorLogging {
  import ConnectionActive._
  import context.dispatcher

  import DistributedPubSubMediator.Publish

  // activate the extension
  val mediator = DistributedPubSubExtension(context.system).mediator

  // have to call after log created
  enableCloseTimeout()

  def receiveRecover: Receive = {
    case event: Event => update(event)
  }

  def receiveCommand: Receive = working orElse {
    case PersistenceFailure(_, _, e) =>
      log.error(e, "persistence failure")
  }

  override def processNewConnected(conn: Connected) {
    persist(conn)(super.processNewConnected(_))
  }

  override def processUpdatePackets(packets: UpdatePackets) {
    if (packets.packets.isEmpty && pendingPackets.isEmpty) {
      super.processUpdatePackets(packets)
    } else {
      persist(packets)(super.processUpdatePackets(_))
    }
  }

  override def dispatchData(packet: Packet)(ctx: ConnectionContext) {
    mediator ! Publish(Namespace.namespaceFor(packet.endpoint), Namespace.OnPacket(packet, ctx))
  }
}

