package spray.contrib.socketio.examples.benchmark

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
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
  val STARTING_MESSAGES_PER_SECOND_RATE = 1

  val SECONDS_TO_TEST_EACH_LOAD_STATE = 10

  val SECONDS_BETWEEN_TESTS = 2

  val MESSAGES_RECEIVED_PER_SECOND_RAMP = 100

  val POST_TEST_RECEPTION_TIMEOUT_WINDOW = 5

  private val MAX_MESSAGES_PER_SECOND_SENT = 200000

  val host = "localhost"
  val port = 8080
  val connect = Http.Connect(host, port)

  protected var concurrencyLevels = Array(
    10, 100, 500, 1000, 2000, 5000)

  implicit val system = ActorSystem()

  case class RoundBegin(concurrentConnections: Int)
  case object OnOpen
  case object OnClose
  case class MessageArrived(roundtrip: Long)
  case class StatsSummary(stats: mutable.Map[Double, SummaryStatistics])

  def main(args: Array[String]) {
    run(args.map(_.toInt))

    readLine("Hit ENTER to exit ...\n")
    system.shutdown()
    system.awaitTermination()

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
      concurrencyLevels = Array.ofDim[Int](concurrencies.size)
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

      val f = socketIOLordTester.ask(RoundBegin(concurrentConnections))(1000.seconds).mapTo[StatsSummary]
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

  protected var clients = List[ActorRef]()

  protected var concurrentConnections = 0

  protected var currentMessagesPerSecond = STARTING_MESSAGES_PER_SECOND_RATE

  protected var isConnectionLost = false

  protected var nConnectionsOpened = 0

  protected var roundtripTimes: mutable.ArrayBuffer[Double] = _

  private var isPostTestTimeout: Boolean = _

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

      if (roundtripTimes.size >= SECONDS_TO_TEST_EACH_LOAD_STATE * currentMessagesPerSecond - 2) {
        isPostTestTimeout = false
      } else {
        log.debug("Expected: " + SECONDS_TO_TEST_EACH_LOAD_STATE * currentMessagesPerSecond + ", got: " + roundtripTimes.size)
      }

  }

  protected def performLoadTest() {
    val statistics = new mutable.HashMap[Double, SummaryStatistics]()

    testRunning = true

    currentMessagesPerSecond = STARTING_MESSAGES_PER_SECOND_RATE
    triggerMessages(statistics, 0.0)
  }

  def triggerMessages(statistics: mutable.Map[Double, SummaryStatistics], _overallEffectiveRate: Double) {
    var overallEffectiveRate = _overallEffectiveRate

    println(concurrentConnections + " connections at currentMessagesPerSecond " + currentMessagesPerSecond + ": ")

    roundtripTimes = new mutable.ArrayBuffer[Double](SECONDS_TO_TEST_EACH_LOAD_STATE * currentMessagesPerSecond)

    val t0 = System.currentTimeMillis
    val expectedDutationPerSend = 1000 / currentMessagesPerSecond
    var count = 0
    var i = 0
    while (i < SECONDS_TO_TEST_EACH_LOAD_STATE) {
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

    overallEffectiveRate = overallEffectiveRate / SECONDS_TO_TEST_EACH_LOAD_STATE
    println("Rate: %.3f ".format(overallEffectiveRate))

    import system.dispatcher
    isPostTestTimeout = true
    system.scheduler.scheduleOnce(POST_TEST_RECEPTION_TIMEOUT_WINDOW.seconds) {
      if (isPostTestTimeout) {
        println("Failed - not all messages received in " + POST_TEST_RECEPTION_TIMEOUT_WINDOW + "s")
        println("Expected: " + SECONDS_TO_TEST_EACH_LOAD_STATE * currentMessagesPerSecond + ", got: " + roundtripTimes.size)
      } else {
        statistics.put(overallEffectiveRate, processRoundtripStats)
      }
      currentMessagesPerSecond += Math.max(100, MESSAGES_RECEIVED_PER_SECOND_RAMP / concurrentConnections)

      if (!isConnectionLost && !isPostTestTimeout && currentMessagesPerSecond < MAX_MESSAGES_PER_SECOND_SENT) {
        system.scheduler.scheduleOnce(SECONDS_BETWEEN_TESTS.seconds) {
          triggerMessages(statistics, overallEffectiveRate)
        }
      } else {
        testRunning = false
        commander ! StatsSummary(statistics)
      }
    }
  }

  protected def triggerChatMessages(totalMessages: Int): Double = {
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

  protected def processRoundtripStats: SummaryStatistics = {
    val stats = new SummaryStatistics()

    roundtripTimes foreach stats.addValue
    println("n: %5d min: %8.0f  mean: %8.0f   max: %8.0f   stdev: %8.0f\n".format(
      stats.getN, stats.getMin, stats.getMean, stats.getMax, stats.getStandardDeviation))

    stats
  }
}
