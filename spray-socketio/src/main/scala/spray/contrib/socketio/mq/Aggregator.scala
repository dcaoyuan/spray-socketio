package spray.contrib.socketio.mq

import akka.ConfigurationException
import akka.actor.ActorContext
import akka.actor.ActorPath
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.singleton.{ ClusterSingletonManagerSettings, ClusterSingletonManager }
import akka.event.EventStream
import akka.remote.DefaultFailureDetectorRegistry
import akka.remote.FailureDetector
import akka.remote.FailureDetectorRegistry
import akka.routing.BroadcastRoutingLogic
import akka.routing.ConsistentHashingRoutingLogic
import akka.routing.RandomRoutingLogic
import akka.routing.RoundRobinRoutingLogic
import akka.routing.RoutingLogic
import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

object Aggregator {

  def props(
    groupRoutingLogic: RoutingLogic,
    failureDetector: FailureDetectorRegistry[ActorPath],
    unreachableReaperInterval: FiniteDuration): Props =
    Props(classOf[Aggregator], groupRoutingLogic, failureDetector, unreachableReaperInterval)

  final case class ReportingData(data: Any)
  final case class Available(address: ActorPath, report: Any)
  final case class Unavailable(address: ActorPath, report: Any)

  case object AskStats
  final case class Stats(reportingData: Map[ActorPath, Any])

  // sent to self only
  private case object ReapUnreachableTick

  class Settings(system: ActorSystem) {
    val config = system.settings.config.getConfig("spray.socketio")
    import config._
    import Helpers.ConfigOps
    import Helpers.Requiring

    val Dispatcher: String = getString("akka.remote.use-dispatcher")
    def configureDispatcher(props: Props): Props = if (Dispatcher.isEmpty) props else props.withDispatcher(Dispatcher)

    val FailureDetectorConfig: Config = config.getConfig("aggregator-failure-detector")
    val AggregatorReportingInterval = FailureDetectorConfig.getMillisDuration("heartbeat-interval")
    val AggregatorFailureDetectorImplementationClass: String = FailureDetectorConfig.getString("implementation-class")
    val AggregatorUnreachableReaperInterval: FiniteDuration = {
      FailureDetectorConfig.getMillisDuration("unreachable-nodes-reaper-interval")
    } requiring (_ > Duration.Zero, "aggregator-failure-detector.unreachable-nodes-reaper-interval must be > 0")

    val groupRoutingLogic = {
      config.getString("routing-logic") match {
        case "random"             => RandomRoutingLogic()
        case "round-robin"        => RoundRobinRoutingLogic()
        case "consistent-hashing" => ConsistentHashingRoutingLogic(system)
        case "broadcast"          => BroadcastRoutingLogic()
        case other                => throw new IllegalArgumentException(s"Unknown 'routing-logic': [$other]")
      }
    }
  }

  private def createAggreratorFailureDetector(system: ActorSystem): FailureDetectorRegistry[ActorPath] = {
    val settings = new Settings(system)
    def createFailureDetector(): FailureDetector =
      FailureDetectorLoader.load(settings.AggregatorFailureDetectorImplementationClass, settings.FailureDetectorConfig, system)

    new DefaultFailureDetectorRegistry(() => createFailureDetector())
  }

  /**
   * name of ClusterSingletonManager actor could not be duplicated in one actor system.
   */
  def singletonManagerNameForAggregate(topic: String) = "aggregatorSingleton-" + topic
  /**
   * All nodes has this role should start this singleton manager, or at least, the
   * oldest/first node should start.
   *
   * The path of singleton will be "/user/singletonManagerName/aggregatorTopic"
   */
  def startAggregator(system: ActorSystem, aggregateTopic: String, role: Option[String]): Unit = {
    val settings = new Settings(system)
    val failureDetector = createAggreratorFailureDetector(system)

    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = props(settings.groupRoutingLogic, failureDetector, settings.AggregatorUnreachableReaperInterval),
        terminationMessage = PoisonPill,
        settings =
          ClusterSingletonManagerSettings(system)
            .withSingletonName(aggregateTopic)
            .withRole(role)),
      name = singletonManagerNameForAggregate(aggregateTopic))
  }

}

class Aggregator(
    groupRoutingLogic: RoutingLogic,
    failureDetector: FailureDetectorRegistry[ActorPath],
    unreachableReaperInterval: FiniteDuration) extends Topic(groupRoutingLogic) {

  import Aggregator._
  import context.dispatcher

  log.info("Aggregator [{}] started", topic)

  val unreachableReaperTask = context.system.scheduler.schedule(unreachableReaperInterval, unreachableReaperInterval, self, ReapUnreachableTick)
  var reportingEntries: Map[ActorPath, Any] = Map.empty

  override def isAggregator = true
  override def postStop(): Unit = {
    super.postStop()
    unreachableReaperTask.cancel()
  }

  override def receive = publishableBehavior orElse reportingBehavior

  def reportingBehavior: Receive = {
    case ReportingData(data: Any) => receiveReportingData(sender().path, data)
    case ReapUnreachableTick      => reapUnreachable()
    case AskStats                 => sender() ! Stats(reportingEntries)
  }

  def receiveReportingData(from: ActorPath, data: Any): Unit = {
    // sender: Actor[akka://SocketIOSystem/user/sharding/Topics/testendpoint#926010083] 
    // path: akka://SocketIOSystem/user/sharding/Topics/testendpoint 
    // address: akka://SocketIOSystem
    // address only contains: akka://SocketIOSystem
    if (failureDetector.isMonitoring(from)) {
      log.debug("Received reporting data from [{}]", from)
    } else {
      log.debug("Received first reporting data from [{}]", from)
    }

    failureDetector.heartbeat(from)
    if (!reportingEntries.contains(from)) {
      log.info("Available: [{}]", data)
      publish(Available(from, data))
    }
    reportingEntries = reportingEntries.updated(from, data)
  }

  def reapUnreachable() {
    val (reachable, unreachable) = reportingEntries.partition { case (path, data) => failureDetector.isAvailable(path) }
    unreachable foreach {
      case (path, data) =>
        log.info("Unavailable: [{}]", data)
        publish(Unavailable(path, data))
        failureDetector.remove(path)
    }
    reportingEntries = reachable
  }

}

/**
 *
 * Utility class to create [[FailureDetector]] instances reflectively.
 */
object FailureDetectorLoader {

  /**
   * Loads and instantiates a given [[FailureDetector]] implementation. The class to be loaded must have a constructor
   * that accepts a [[com.typesafe.config.Config]] and an [[EventStream]] parameter. Will throw ConfigurationException
   * if the implementation cannot be loaded.
   *
   * @param fqcn Fully qualified class name of the implementation to be loaded.
   * @param config Configuration that will be passed to the implementation
   * @param system ActorSystem to be used for loading the implementation
   * @return A configured instance of the given [[FailureDetector]] implementation
   */
  def load(fqcn: String, config: Config, system: ActorSystem): FailureDetector = {
    system.asInstanceOf[ExtendedActorSystem].dynamicAccess.createInstanceFor[FailureDetector](
      fqcn, List(
        classOf[Config] -> config,
        classOf[EventStream] -> system.eventStream)).recover({
        case e => throw new ConfigurationException(
          s"Could not create custom failure detector [$fqcn] due to: ${e.toString}", e)
      }).get
  }

  /**
   * Loads and instantiates a given [[FailureDetector]] implementation. The class to be loaded must have a constructor
   * that accepts a [[com.typesafe.config.Config]] and an [[EventStream]] parameter. Will throw ConfigurationException
   * if the implementation cannot be loaded. Use [[FailureDetectorLoader#load]] if no implicit [[ActorContext]] is
   * available.
   *
   * @param fqcn Fully qualified class name of the implementation to be loaded.
   * @param config Configuration that will be passed to the implementation
   * @return
   */
  def apply(fqcn: String, config: Config)(implicit ctx: ActorContext) = load(fqcn, config, ctx.system)

}

object Helpers {
  import java.util.concurrent.TimeUnit

  /**
   * Implicit class providing `requiring` methods. This class is based on
   * `Predef.ensuring` in the Scala standard library. The difference is that
   * this class's methods throw `IllegalArgumentException`s rather than
   * `AssertionError`s.
   *
   * An example adapted from `Predef`'s documentation:
   * {{{
   * import akka.util.Helpers.Requiring
   *
   * def addNaturals(nats: List[Int]): Int = {
   *   require(nats forall (_ >= 0), "List contains negative numbers")
   *   nats.foldLeft(0)(_ + _)
   * } requiring(_ >= 0)
   * }}}
   *
   * @param value The value to check.
   */
  @inline final implicit class Requiring[A](val value: A) extends AnyVal {
    /**
     * Check that a condition is true. If true, return `value`, otherwise throw
     * an `IllegalArgumentException` with the given message.
     *
     * @param cond The condition to check.
     * @param msg The message to report if the condition isn't met.
     */
    @inline def requiring(cond: Boolean, msg: ⇒ Any): A = {
      require(cond, msg)
      value
    }

    /**
     * Check that a condition is true for the `value`. If true, return `value`,
     * otherwise throw an `IllegalArgumentException` with the given message.
     *
     * @param cond The function used to check the `value`.
     * @param msg The message to report if the condition isn't met.
     */
    @inline def requiring(cond: A ⇒ Boolean, msg: ⇒ Any): A = {
      require(cond(value), msg)
      value
    }
  }

  /**
   * INTERNAL API
   */
  final implicit class ConfigOps(val config: Config) extends AnyVal {
    def getMillisDuration(path: String): FiniteDuration = getDuration(path, TimeUnit.MILLISECONDS)

    def getNanosDuration(path: String): FiniteDuration = getDuration(path, TimeUnit.NANOSECONDS)

    private def getDuration(path: String, unit: TimeUnit): FiniteDuration =
      Duration(config.getDuration(path, unit), unit)
  }

}