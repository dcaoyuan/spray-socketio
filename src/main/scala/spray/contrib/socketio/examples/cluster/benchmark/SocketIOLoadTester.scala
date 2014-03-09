package spray.contrib.socketio.examples.benchmark.cluster

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import java.io.BufferedWriter
import java.io.FileNotFoundException
import java.io.FileWriter
import java.io.IOException
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.can.Http
import spray.can.websocket

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

object SocketIOLoadTester {
  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")

  val postTestReceptionTimeout = config.getInt("post-test-reception-timeout")
  val initailMessagesPerSecond = config.getInt("initail-messages-per-second")
  val secondsToTestEachLoadState = config.getInt("seconds-to-test-each-load-state")
  val secondsBetweenRounds = config.getInt("seconds-between-rounds")
  val messageReceivedPerSecondRamp = config.getInt("message-received-per-second-ramp")
  val maxMessagesPerSecond = config.getInt("max-messages-per-second")

  val host = config.getString("host")
  val port = config.getInt("port")
  var concurrencyLevels = config.getIntList("concurrencyLevels")

  val connect = Http.Connect(host, port)

  implicit val system = ActorSystem()

  case class RoundBegin(concurrentConnections: Int)
  case object OnOpen
  case object OnClose
  case class MessageArrived(roundtrip: Long)
  case class StatsSummary(stats: mutable.Map[Double, SummaryStatistics])

  def main(args: Array[String]) {
    run(args.map(_.toInt))
  }

  def run(concurrencies: Array[Int]) {
    var file: BufferedWriter = null
    try {
      file = new BufferedWriter(new FileWriter(System.currentTimeMillis + ".log"))
    } catch {
      case ex: FileNotFoundException => ex.printStackTrace
      case ex: IOException           => ex.printStackTrace
    }

    if (concurrencies.size > 0) {
      print("Using custom concurrency levels: ")
      concurrencyLevels = new java.util.ArrayList[Integer]()
      println
      var i = 0
      for (concurrency <- concurrencies) {
        concurrencyLevels(i) = concurrency
        i += 1
        print(concurrency + " ")
      }

      println
    }

    var i = 0
    while (i < concurrencyLevels.length) {
      val concurrentConnections = concurrencyLevels(i)

      val socketIOLordTester = system.actorOf(Props(new SocketIOLoadTester), "socketioclients")

      val f = socketIOLordTester.ask(RoundBegin(concurrentConnections))(100.minutes).mapTo[StatsSummary]
      val summaryStats = Await.result(f, Duration.Inf).stats

      system.stop(socketIOLordTester)

      for ((messageRate, stats) <- summaryStats) {
        try {
          file.write("%d,%f,%d,%f,%f,%f,%f\n".format(
            concurrentConnections, messageRate, stats.getN,
            stats.getMin, stats.getMean, stats.getMax,
            stats.getStandardDeviation))
          println("Wrote results of run to disk.")
        } catch {
          case ex: IOException => ex.printStackTrace
        }
      }
      i += 1
    }

    try {
      file.close
    } catch {
      case ex: IOException => ex.printStackTrace
    }

  }

}

class SocketIOLoadTester extends Actor with ActorLogging {
  import SocketIOLoadTester._

  final case class RoundContext(timeoutHandler: Option[Cancellable], statistics: mutable.Map[Double, SummaryStatistics], overallEffectiveRate: Double)

  private var clients = List[ActorRef]()

  private var concurrentConnections = 0

  private var currentMessagesPerSecond = initailMessagesPerSecond

  private var isConnectionLost = false

  private var nConnectionsOpened = 0

  private var roundtripTimes: mutable.ArrayBuffer[Double] = _

  private var isPostTestTimeout: Boolean = _
  private var roundContext: RoundContext = _

  private var testRunning: Boolean = _

  private var commander: ActorRef = _

  def receive = {
    case RoundBegin(concConnections) =>
      commander = sender()
      val t0 = System.currentTimeMillis
      concurrentConnections = concConnections
      println("---------------- CONCURRENCY " + concurrentConnections + " ----------------")
      var i = 0
      while (i < concurrentConnections) {
        val client = system.actorOf(Props(new SocketIOTestClient(connect, self)))
        clients ::= client
        i += 1
      }

      println("Clients created in " + ((System.currentTimeMillis - t0) / 1000.0) + "s")
      println("Woken up - time to start load test!")

    case OnOpen =>
      nConnectionsOpened += 1
      if (nConnectionsOpened == concurrentConnections) {
        println("All " + concurrentConnections + " clients connected successfully.")
        performLoadTest()
      }

    case OnClose =>
      if (testRunning) {
        isConnectionLost = true
        println("Failed - lost a connection. Shutting down.")
      }

    case MessageArrived(roundtripTime: Long) =>
      roundtripTimes += roundtripTime

      if (roundtripTimes.size >= secondsToTestEachLoadState * currentMessagesPerSecond) {
        isPostTestTimeout = false
        roundContext match {
          case null =>
          case RoundContext(timeoutHandler, statistics, overallEffectiveRate) =>
            roundContext.timeoutHandler foreach (_.cancel)
            goon(statistics, overallEffectiveRate)
        }
      } else {
        log.debug("Expected: " + secondsToTestEachLoadState * currentMessagesPerSecond + ", got: " + roundtripTimes.size)
      }

  }

  private def performLoadTest() {
    val statistics = new mutable.HashMap[Double, SummaryStatistics]()

    testRunning = true

    currentMessagesPerSecond = initailMessagesPerSecond
    triggerMessages(statistics, 0.0)
  }

  private def triggerMessages(statistics: mutable.Map[Double, SummaryStatistics], _overallEffectiveRate: Double) {
    var overallEffectiveRate = _overallEffectiveRate

    println(concurrentConnections + " connections at currentMessagesPerSecond " + currentMessagesPerSecond + ": ")

    roundtripTimes = new mutable.ArrayBuffer[Double](secondsToTestEachLoadState * currentMessagesPerSecond)

    val t0 = System.currentTimeMillis
    val expectedDutationPerSend = 1000 / currentMessagesPerSecond
    var count = 0
    var i = 0
    while (i < secondsToTestEachLoadState) {
      // usually we hope triggerChatMessages will be processed in extractly 1 second.
      val messageSendStartTime = System.currentTimeMillis
      val effectiveRate = triggerChatMessages(currentMessagesPerSecond)
      val duration = System.currentTimeMillis - messageSendStartTime
      val delta = expectedDutationPerSend - duration
      // TODO flow control here according to delta here?
      count += currentMessagesPerSecond
      overallEffectiveRate += effectiveRate
      i += 1
    }
    println(count + " messages sent in " + (System.currentTimeMillis - t0) + "ms")

    overallEffectiveRate = overallEffectiveRate / secondsToTestEachLoadState
    println("Rate: %.3f ".format(overallEffectiveRate))

    import system.dispatcher
    isPostTestTimeout = true
    roundContext = RoundContext(
      Some(system.scheduler.scheduleOnce(postTestReceptionTimeout.seconds) {
        if (isPostTestTimeout) {
          println("Failed - not all messages received in " + postTestReceptionTimeout + "s")
          println("Expected: " + secondsToTestEachLoadState * currentMessagesPerSecond + ", got: " + roundtripTimes.size)
        } else {
          goon(statistics, overallEffectiveRate)
        }
      }), statistics, overallEffectiveRate)
  }

  private def goon(statistics: mutable.Map[Double, SummaryStatistics], overallEffectiveRate: Double) {
    statistics.put(overallEffectiveRate, processRoundtripStats)
    currentMessagesPerSecond += Math.max(100, messageReceivedPerSecondRamp / concurrentConnections)

    if (!isConnectionLost && !isPostTestTimeout && currentMessagesPerSecond < maxMessagesPerSecond) {
      import system.dispatcher
      system.scheduler.scheduleOnce(secondsBetweenRounds.seconds) {
        triggerMessages(statistics, overallEffectiveRate)
      }
    } else {
      testRunning = false
      commander ! StatsSummary(statistics)
    }
  }

  private def triggerChatMessages(totalMessages: Int): Double = {
    val start = System.currentTimeMillis

    var senders = clients
    var i = 0
    while (i < totalMessages) {
      senders.head ! SocketIOTestClient.SendTimestampedChat
      senders = if (senders.tail.isEmpty) clients else senders.tail
      i += 1
    }

    totalMessages / ((System.currentTimeMillis - start) / 1000.0)
  }

  private def processRoundtripStats: SummaryStatistics = {
    val stats = new SummaryStatistics()

    roundtripTimes foreach stats.addValue
    println("n: %5d min: %8.0f  mean: %8.0f   max: %8.0f   stdev: %8.0f\n".format(
      stats.getN, stats.getMin, stats.getMean, stats.getMax, stats.getStandardDeviation))

    stats
  }
}
