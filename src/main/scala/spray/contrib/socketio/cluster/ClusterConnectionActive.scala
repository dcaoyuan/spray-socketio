package spray.contrib.socketio.cluster

import akka.actor.{ActorLogging, ActorRef, ActorSystem}
import akka.contrib.pattern.{ DistributedPubSubMediator, DistributedPubSubExtension, ShardRegion, ClusterSharding }
import akka.persistence.EventsourcedProcessor
import spray.contrib.socketio
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.{Namespace, ConnectionContext, ConnectionActiveResolver, ConnectionActive}

object ClusterConnectionActiveResolver {
  lazy val idExtractor: ShardRegion.IdExtractor = {
    case cmd: socketio.ConnectionActive.Command => (cmd.sessionId, cmd)
  }

  lazy val shardResolver: ShardRegion.ShardResolver = {
    case cmd: socketio.ConnectionActive.Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }

  def apply(system: ActorSystem) = new ClusterConnectionActiveResolver(system)
}
final class ClusterConnectionActiveResolver(system: ActorSystem) extends socketio.ConnectionActiveResolver {
  import ConnectionActive._

  private lazy val resolverActor: ActorRef = ClusterSharding(system).shardRegion(shardName)

  def createActive(sessionId: String) {
    // will be created automatically. TODO how to drop them when closed
  }

  def dispatch(cmd: Command) {
    resolverActor ! cmd
  }
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
class ClusterConnectionActive(val resolver: ConnectionActiveResolver) extends ConnectionActive with EventsourcedProcessor with ActorLogging {
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

  def receiveCommand: Receive = working

  override def processNewConnecting(connecting: Connecting) {
    persist(Connected(connecting.sessionId, connecting.query, connecting.origins)) { event =>
      update(event)
      connectionContext.foreach(_.bindTransport(connecting.transport))
      connected()
    }
  }

  override def dispatchData(packet: Packet)(ctx: ConnectionContext) {
    mediator ! Publish(Namespace.namespaceFor(packet.endpoint), Namespace.OnPacket(packet, ctx))
  }
}

