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

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)

  val mediatorName: String = "socketioMediator"
  val mediatorSingleton: String = "active"

}

class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  private val log = Logging(system, "SocketIO")

  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    val connRole: String = "connectionActive"
    val enableConnPersistence: Boolean = config.getBoolean("server.enable-connectionactive-persistence")
    val schedulerTickDuration: FiniteDuration = Duration(config.getDuration("scheduler.tick-duration", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    val schedulerTicksPerWheel: Int = config.getInt("scheduler.ticks-per-wheel")
  }

  lazy val localMediator = system.actorOf(LocalMediator.props(), name = SocketIOExtension.mediatorName)

  /** No lazy, need to start immediately to accept broadcast etc. */
  val broadcastMediator = if (Settings.isCluster) DistributedPubSubExtension(system).mediator else localMediator

  /** No lazy, need to start immediately to accept subscriptions msg etc. */
  val namespaceMediator = if (Settings.isCluster) {
    val cluster = Cluster(system)
    if (cluster.getSelfRoles.contains(Settings.connRole)) {
      val routingLogic = Settings.config.getString("routing-logic") match {
        case "random"             => RandomRoutingLogic()
        case "round-robin"        => RoundRobinRoutingLogic()
        case "consistent-hashing" => ConsistentHashingRoutingLogic(system)
        case "broadcast"          => BroadcastRoutingLogic()
        case other                => throw new IllegalArgumentException(s"Unknown 'routing-logic': [$other]")
      }
      val ref = system.actorOf(DistributedBalancingPubSubMediator.props(Some(Settings.connRole), routingLogic), name = SocketIOExtension.mediatorName)
      ClusterReceptionistExtension(system).registerService(ref)
      ref
    } else {
      system.deadLetters
    }
  } else localMediator

  lazy val connectionActiveProps: Props = if (Settings.enableConnPersistence) {
    PersistentConnectionActive.props(namespaceMediator, broadcastMediator)
  } else {
    TransientConnectionActive.props(namespaceMediator, broadcastMediator)
  }

  if (Settings.isCluster) {
    ConnectionActive.startShard(system, connectionActiveProps)
  }

  lazy val resolver = if (Settings.isCluster) {
    ClusterSharding(system).shardRegion(ConnectionActive.shardName)
  } else {
    system.actorOf(LocalConnectionActiveResolver.props(localMediator, connectionActiveProps), name = ConnectionActive.shardName)
  }

  lazy val scheduler: Scheduler = {
    import scala.collection.JavaConverters._
    log.info("Using a dedicated scheduler for socketio with 'spray.socketio.scheduler.tick-duration' [{} ms].", Settings.schedulerTickDuration.toMillis)

    val cfg = ConfigFactory.parseString(
      s"socketio.scheduler.tick-duration=${Settings.schedulerTickDuration.toMillis}ms").withFallback(
        system.settings.config)
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
