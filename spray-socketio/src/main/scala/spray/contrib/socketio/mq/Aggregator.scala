package spray.contrib.socketio.mq

import akka.ConfigurationException
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Props
import akka.event.EventStream
import akka.remote.DefaultFailureDetectorRegistry
import akka.remote.FailureDetector
import akka.remote.FailureDetectorRegistry
import akka.routing.RoutingLogic
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object Aggregator {

  def props[T](
    groupRoutingLogic: RoutingLogic,
    failureDetector: FailureDetectorRegistry[Address],
    unreachableReaperInterval: FiniteDuration): Props = Props(classOf[Aggregator[T]], groupRoutingLogic, failureDetector, unreachableReaperInterval)

  final case class ReportingData[T](data: T)
  final case class Awailable[T](address: Address, report: T)
  final case class Unreachable[T](address: Address, report: T)

  case object Stats
  final case class Stats[T](reportingData: Map[Address, T])

  // sent to self only
  case object ReapUnreachableTick

  val config = ConfigFactory.load().getConfig("spray.socketio")
  val settings = new Settings(config)
  class Settings(config: Config) {
    import config._
    import Helpers.ConfigOps
    import Helpers.Requiring

    val Dispatcher: String = getString("akka.remote.use-dispatcher")
    def configureDispatcher(props: Props): Props = if (Dispatcher.isEmpty) props else props.withDispatcher(Dispatcher)

    val FailureDetectorConfig: Config = config.getConfig("aggregator-failure-detector")
    val AggregatorFailureDetectorImplementationClass: String = FailureDetectorConfig.getString("implementation-class")
    val AggregatorUnreachableReaperInterval: FiniteDuration = {
      FailureDetectorConfig.getMillisDuration("unreachable-nodes-reaper-interval")
    } requiring (_ > Duration.Zero, "watch-failure-detector.unreachable-nodes-reaper-interval must be > 0")
  }

  protected def createAggregator(system: ExtendedActorSystem, groupRoutingLogic: RoutingLogic): ActorRef = {
    import settings._
    val failureDetector = createRemoteWatcherFailureDetector(system)
    system.systemActorOf(
      configureDispatcher(
        Aggregator.props(
          groupRoutingLogic,
          failureDetector,
          unreachableReaperInterval = AggregatorUnreachableReaperInterval)),
      "aggregator")
  }

  protected def createRemoteWatcherFailureDetector(system: ExtendedActorSystem): FailureDetectorRegistry[Address] = {
    def createFailureDetector(): FailureDetector =
      FailureDetectorLoader.load(settings.AggregatorFailureDetectorImplementationClass, settings.FailureDetectorConfig, system)

    new DefaultFailureDetectorRegistry(() => createFailureDetector())
  }
}

class Aggregator[T: ClassTag](
    groupRoutingLogic: RoutingLogic,
    failureDetector: FailureDetectorRegistry[Address],
    unreachableReaperInterval: FiniteDuration) extends Topic(groupRoutingLogic) {

  import Aggregator._
  import context.dispatcher

  val unreachableReaperTask = context.system.scheduler.schedule(unreachableReaperInterval, unreachableReaperInterval, self, ReapUnreachableTick)
  var reportingEntries: Map[Address, T] = Map.empty

  override def postStop(): Unit = {
    super.postStop()
    unreachableReaperTask.cancel()
  }

  override def receive = processMessage orElse {
    case ReportingData(data: T) => receiveReportingData(data)
    case ReapUnreachableTick    => reapUnreachable()
    case Stats                  => sender() ! Stats(reportingEntries)
  }

  def receiveReportingData(data: T): Unit = {
    val from = sender().path.address

    if (failureDetector.isMonitoring(from)) {
      log.debug("Received reporting data from [{}]", from)
    } else {
      log.debug("Received first reporing data from [{}]", from)
    }

    failureDetector.heartbeat(from)
    if (!reportingEntries.contains(from)) {
      deliverMessage(Awailable(from, data))
    }
    reportingEntries = reportingEntries.updated(from, data)
  }

  def reapUnreachable() {
    val (reachable, unreachable) = reportingEntries.partition { case (a, data) => failureDetector.isAvailable(a) }
    unreachable foreach {
      case (a, data) =>
        log.warning("Detected unreachable: [{}]", a)
        deliverMessage(Unreachable(a, data))
        failureDetector.remove(a)
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