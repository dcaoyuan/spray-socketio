package spray.contrib.socketio

import akka.actor._
import akka.contrib.pattern._
import scala.Some
import akka.cluster.Cluster

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)

  val shardName: String = "connectionActives"

  val mediatorName: String = "socketioMediator"
  val mediatorSingleton: String = "active"

  lazy val idExtractor: ShardRegion.IdExtractor = {
    case cmd: ConnectionActive.Command => (cmd.sessionId, cmd)
  }

  lazy val shardResolver: ShardRegion.ShardResolver = {
    case cmd: ConnectionActive.Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }
}

class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    val ConnRole: String = "connectionActive"
  }

  import Settings._

  private lazy val localMediator = system.actorOf(Props(classOf[LocalMediator]), name = SocketIOExtension.mediatorName)

  private lazy val localResolver = system.actorOf(Props(classOf[LocalConnectionActiveResolver], localMediator, broadcastMediator), name = SocketIOExtension.shardName)

  /**
   * Need to start immediately to accept broadcast etc.
   */
  val broadcastMediator = if (isCluster) DistributedPubSubExtension(system).mediator else localMediator

  lazy val namespaceMediator = if (isCluster) {
    val cluster = Cluster(system)
    if (cluster.getSelfRoles.contains(ConnRole)) {
      val ref = system.actorOf(Props(classOf[DistributedBalancingPubSubMediator]), name = SocketIOExtension.mediatorName)
      ClusterReceptionistExtension(system).registerService(ref)
      ref
    } else {
      system.deadLetters
    }
  } else localMediator

  if (isCluster) {
    ClusterReceptionistExtension(system)
    ClusterSharding(system).start(
      typeName = SocketIOExtension.shardName,
      entryProps = Some(Props(classOf[ClusterConnectionActive], namespaceMediator, broadcastMediator)),
      idExtractor = SocketIOExtension.idExtractor,
      shardResolver = SocketIOExtension.shardResolver)
    ClusterReceptionistExtension(system).registerService(
      ClusterSharding(system).shardRegion(SocketIOExtension.shardName))
  }

  lazy val resolver = if (isCluster) {
    ClusterSharding(system).shardRegion(SocketIOExtension.shardName)
  } else localResolver

}
