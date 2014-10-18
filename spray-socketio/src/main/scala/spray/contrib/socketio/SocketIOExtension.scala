package spray.contrib.socketio

import akka.actor._
import akka.contrib.pattern._
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
import spray.contrib.socketio.namespace.LocalNamespaceRegion
import spray.contrib.socketio.namespace.Namespace

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)

  val mediatorName: String = "socketioMediator"
}

class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  private val log = Logging(system, "SocketIO")

  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    val enableSessionPersistence: Boolean = config.getBoolean("server.enable-connectionsession-persistence")
    val schedulerTickDuration: FiniteDuration = Duration(config.getDuration("scheduler.tick-duration", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    val schedulerTicksPerWheel: Int = config.getInt("scheduler.ticks-per-wheel")
  }

  private lazy val groupRoutingLogic = {
    Settings.config.getString("routing-logic") match {
      case "random"             => RandomRoutingLogic()
      case "round-robin"        => RoundRobinRoutingLogic()
      case "consistent-hashing" => ConsistentHashingRoutingLogic(system)
      case "broadcast"          => BroadcastRoutingLogic()
      case other                => throw new IllegalArgumentException(s"Unknown 'routing-logic': [$other]")
    }
  }

  private lazy val localMediator = system.actorOf(LocalMediator.props(), name = SocketIOExtension.mediatorName)
  private lazy val localSessionRegion = system.actorOf(LocalConnectionSessionRegion.props(TransientConnectionSession.props(localMediator, localMediator)), name = ConnectionSession.shardName)
  private lazy val localNamespaceRegion = system.actorOf(LocalNamespaceRegion.props(Namespace.props(localMediator, groupRoutingLogic)), name = Namespace.shardName)

  lazy val mediator = if (Settings.isCluster) DistributedPubSubExtension(system).mediator else localMediator

  lazy val sessionProps: Props = if (Settings.enableSessionPersistence) {
    PersistentConnectionSession.props(mediator, mediator)
  } else {
    TransientConnectionSession.props(mediator, mediator)
  }

  lazy val namespaceProps: Props = {
    Namespace.props(mediator, groupRoutingLogic)
  }

  lazy val clusterClient = {
    import scala.collection.JavaConversions._
    val initialContacts = system.settings.config.getStringList("spray.socketio.cluster.client-initial-contacts").toSet
    system.actorOf(ClusterClient.props(initialContacts map system.actorSelection), "socketio-cluster-client")
  }

  /**
   * Should start sharding before by: ConnectionSession.startSharding(system, Option[sessionProps])
   */
  lazy val sessionRegion = if (Settings.isCluster) {
    ClusterSharding(system).shardRegion(ConnectionSession.shardName)
  } else {
    localSessionRegion
  }

  /**
   * Should start sharding before by: Namespace.startSharding(system, Option[namespcaeProps])
   */
  lazy val namespaceRegion = if (Settings.isCluster) {
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
