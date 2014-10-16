package spray.contrib.socketio

import akka.actor._
import akka.contrib.pattern._
import akka.cluster.Cluster
import akka.dispatch.MonitorableThreadFactory
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.routing.{ BroadcastRoutingLogic, ConsistentHashingRoutingLogic, RoundRobinRoutingLogic, RandomRoutingLogic }
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import spray.contrib.socketio
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import spray.contrib.socketio.namespace.DistributedBalancingPubSubProxy
import spray.contrib.socketio.namespace.LocalNamespaceRegion
import spray.contrib.socketio.namespace.Namespace

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)

  val mediatorName: String = "socketioMediator"
  val mediatorSingleton: String = "socketiosession"

}

class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  private val log = Logging(system, "SocketIO")

  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val sessionRole: String = "connectionSession"
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    val enableSessionPersistence: Boolean = config.getBoolean("server.enable-connectionsession-persistence")
    val schedulerTickDuration: FiniteDuration = Duration(config.getDuration("scheduler.tick-duration", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    val schedulerTicksPerWheel: Int = config.getInt("scheduler.ticks-per-wheel")
    val namespaceGroup = config.getString("server.namespace-group-name")
  }

  lazy val sessionProps: Props = if (Settings.enableSessionPersistence) {
    PersistentConnectionSession.props(broadcastMediator, broadcastMediator)
  } else {
    TransientConnectionSession.props(broadcastMediator, broadcastMediator)
  }

  lazy val namespaceProps: Props = Namespace.props(broadcastMediator)

  lazy val clusterClient = {
    import scala.collection.JavaConversions._
    val initialContacts = system.settings.config.getStringList("spray.socketio.cluster.client-initial-contacts").toSet
    system.actorOf(ClusterClient.props(initialContacts map system.actorSelection), "socketio-cluster-client")
  }

  lazy val localMediator = system.actorOf(LocalMediator.props(), name = SocketIOExtension.mediatorName)

  lazy val localSessionRegion = system.actorOf(LocalConnectionSessionRegion.props(localMediator, sessionProps), name = ConnectionSession.shardName)
  lazy val localNamespaceRegion = system.actorOf(LocalNamespaceRegion.props(localMediator), name = Namespace.shardName)

  /** No lazy, need to start immediately to accept broadcast etc. */
  lazy val broadcastMediator = if (Settings.isCluster) DistributedPubSubExtension(system).mediator else localMediator

  /**
   * No lazy, need to start immediately to accept subscriptions msg etc.
   * namespaceMediator is used by client outside of cluster.
   */
  lazy val namespaceMediator = if (Settings.isCluster) {
    val cluster = Cluster(system)
    if (cluster.getSelfRoles.contains(Settings.sessionRole)) {
      val routingLogic = Settings.config.getString("routing-logic") match {
        case "random"             => RandomRoutingLogic()
        case "round-robin"        => RoundRobinRoutingLogic()
        case "consistent-hashing" => ConsistentHashingRoutingLogic(system)
        case "broadcast"          => BroadcastRoutingLogic()
        case other                => throw new IllegalArgumentException(s"Unknown 'routing-logic': [$other]")
      }
      val mediator = system.actorOf(DistributedBalancingPubSubMediator.props(Some(Settings.sessionRole), routingLogic), name = SocketIOExtension.mediatorName)
      ClusterReceptionistExtension(system).registerService(mediator)
      mediator
    } else {
      system.deadLetters
    }
  } else localMediator

  lazy val mediatorProxy = if (Settings.isCluster) {
    broadcastMediator //system.actorOf(DistributedBalancingPubSubProxy.props(s"/user/${SocketIOExtension.mediatorName}", Settings.namespaceGroup, clusterClient))
  } else {
    localMediator
  }

  lazy val sessionRegion = if (Settings.isCluster) {
    //ConnectionSession.startSharding(system, None)
    ClusterSharding(system).shardRegion(ConnectionSession.shardName)
  } else {
    localSessionRegion
  }

  lazy val namespaceRegion = if (Settings.isCluster) {
    //Namespace.startSharding(system, None)
    ClusterSharding(system).shardRegion(Namespace.shardName)
  } else {
    localNamespaceRegion
  }

  lazy val sessionClient = if (Settings.isCluster) {
    ConnectionSession(system).clusterClient
  } else {
    localSessionRegion
  }

  lazy val namespaceClient = if (Settings.isCluster) {
    Namespace(system).clusterClient
  } else {
    localNamespaceRegion
  }

  lazy val scheduler: Scheduler = {
    import scala.collection.JavaConverters._
    log.info("Using a dedicated scheduler for socketio with 'spray.socketio.scheduler.tick-duration' [{} ms].", Settings.schedulerTickDuration.toMillis)

    val cfg = ConfigFactory.parseString(
      s"socketio.scheduler.tick-duration=${Settings.schedulerTickDuration.toMillis}ms").withFallback(system.settings.config)
    val threadFactory = system.threadFactory match {
      case tf: MonitorableThreadFactory => tf.withName(tf.name + "-socketio-scheduler")
      case tf                           => tf
    }
    system.dynamicAccess.createInstanceFor[Scheduler](system.settings.SchedulerClass, immutable.Seq(
      classOf[Config] -> cfg,
      classOf[LoggingAdapter] -> log,
      classOf[ThreadFactory] -> threadFactory)).get
  }
}
