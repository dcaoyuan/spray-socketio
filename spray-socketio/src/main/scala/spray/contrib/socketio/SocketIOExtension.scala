package spray.contrib.socketio

import akka.actor._
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
import spray.contrib.socketio.mq.LocalTopicRegion
import spray.contrib.socketio.mq.Topic

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)
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

  private lazy val localSessionRegion = system.actorOf(LocalConnectionSessionRegion.props(TransientConnectionSession.props()), name = ConnectionSession.shardName)
  private lazy val localTopicRegion = system.actorOf(LocalTopicRegion.props(Topic.props(groupRoutingLogic)), name = Topic.shardName)

  lazy val sessionProps: Props = if (Settings.enableSessionPersistence) {
    PersistentConnectionSession.props()
  } else {
    TransientConnectionSession.props()
  }

  lazy val topicProps: Props = {
    Topic.props(groupRoutingLogic)
  }

  /**
   * Should start sharding before by: ConnectionSession.startSharding(system, Option[sessionProps])
   */
  def sessionRegion = if (Settings.isCluster) {
    ConnectionSession.shardRegion(system)
  } else {
    localSessionRegion
  }

  /**
   * Should start sharding before by: Topic.startSharding(system, Option[topicProps])
   */
  def topicRegion = if (Settings.isCluster) {
    Topic.shardRegion(system)
  } else {
    localTopicRegion
  }

  def sessionClient = if (Settings.isCluster) {
    ConnectionSession(system).clusterClient
  } else {
    localSessionRegion
  }

  def topicClient = if (Settings.isCluster) {
    Topic(system).clusterClient
  } else {
    localTopicRegion
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
