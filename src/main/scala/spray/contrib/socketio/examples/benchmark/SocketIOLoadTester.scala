package spray.contrib.socketio.examples.benchmark

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
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.can.Http
import spray.can.websocket
import spray.contrib.socketio.examples.benchmark.SocketIOTestClient.MessageArrived
import spray.contrib.socketio.examples.benchmark.SocketIOTestClient.OnClose
import spray.contrib.socketio.examples.benchmark.SocketIOTestClient.OnOpen

object SocketIOLoadTester {
  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")

  val postTestReceptionTimeout = config.getInt("post-test-reception-timeout")
  val initailMessagesPerSecond = config.getInt("initail-messages-sent-per-second")
  val nSecondsToTestEachLoadState = config.getInt("seconds-to-test-each-load-state")
  val secondsBetweenRounds = config.getInt("seconds-between-rounds")
  val nMessageSentPerSecondRamp = config.getInt("messages-sent-per-second-ramp")
  val maxMessagesSentPerSecond = config.getInt("max-messages-sent-per-second")
  val isBroadcast = config.getBoolean("broadcast")

  val host = config.getString("host")
  val port = config.getInt("port")

  val connect = Http.Connect(host, port)

  var concurrencyLevels = config.getIntList("concurrencyLevels")

  implicit val system = ActorSystem()

  case class RoundBegin(concurrentConnections: Int)
  case class StatsSummary(stats: mutable.Map[Double, SummaryStatistics])
  case object ReceivingTimeout

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

  final case class RoundContext(receivingTimeoutHandler: Option[Cancellable], statistics: mutable.Map[Double, SummaryStatistics], overallEffectiveRate: Double)

  private var clients = List[ActorRef]()

  private var nConnections = 0

  private var nMessagesSentPerSecond = initailMessagesPerSecond

  private var isConnectionLost = false

  private var nConnectionsOpened = 0

  private var roundtripTimes: mutable.ArrayBuffer[Double] = _

  private var roundContext: RoundContext = _

  private var isTestRunning: Boolean = _

  private var commander: ActorRef = _

  private var t0 = System.currentTimeMillis

  private var nMessagesSent: Int = _
  private var isMessagesSent: Boolean = _

  def receive = {
    case RoundBegin(nConns) =>
      commander = sender()
      nConnections = nConns
      println("---------------- Concurrent connections " + nConnections + { if (isBroadcast) " (broadcast)" else " (single bounce)" } + " ----------------")
      var i = 0
      while (i < nConnections) {
        val client = system.actorOf(Props(new SocketIOTestClient(connect, self)))
        clients ::= client
        i += 1
      }

      println("Clients created in " + ((System.currentTimeMillis - t0) / 1000.0) + "s.")

    case OnOpen =>
      nConnectionsOpened += 1
      if (nConnectionsOpened == nConnections) {
        println("\nAll " + nConnections + " clients connected successfully in " + ((System.currentTimeMillis - t0) / 1000.0) + "s.")
        println("Woken up - time to start load test!\n")
        performLoadTest()
      } else if (nConnectionsOpened % 100 == 0) {
        print(nConnectionsOpened + "... ")
      }

    case OnClose =>
      if (isTestRunning) {
        isConnectionLost = true
        println("Failed - lost a connection. Shutting down.")
      }

    case MessageArrived(roundtripTime: Long) =>
      roundtripTimes += roundtripTime

      if (isMessagesSent && roundtripTimes.size >= nMessagesExpected) {
        roundContext match {
          case null =>
          case RoundContext(receivingTimeoutHandler, statistics, overallEffectiveRate) =>
            receivingTimeoutHandler foreach (_.cancel)
            goon(statistics, overallEffectiveRate)
        }
      } else {
        log.debug("Expected: " + nMessagesExpected + ", got: " + roundtripTimes.size)
      }

    case ReceivingTimeout =>
      if (isMessagesSent && roundtripTimes.size >= nMessagesExpected) {
        roundContext match {
          case null =>
          case RoundContext(receivingTimeoutHandler, statistics, overallEffectiveRate) =>
            goon(statistics, overallEffectiveRate)
        }
      } else {
        println("Failed - not all messages received in " + postTestReceptionTimeout + "s")
        println("Expected: " + nMessagesExpected + ", got: " + roundtripTimes.size)
      }
  }

  private def nMessagesExpected = if (isBroadcast) nMessagesSent * nConnections else nMessagesSent

  private def performLoadTest() {
    val statistics = new mutable.HashMap[Double, SummaryStatistics]()

    isTestRunning = true

    nMessagesSentPerSecond = initailMessagesPerSecond
    triggerMessages(statistics, 0.0)
  }

  private def triggerMessages(statistics: mutable.Map[Double, SummaryStatistics], _overallEffectiveRate: Double) {
    isMessagesSent = false

    var overallEffectiveRate = _overallEffectiveRate

    println(nConnections + " connections at sending rate " + nMessagesSentPerSecond + " msgs/s: ")

    roundtripTimes = new mutable.ArrayBuffer[Double](nSecondsToTestEachLoadState * nMessagesSentPerSecond * { if (isBroadcast) nConnections else 1 })

    val t0 = System.currentTimeMillis
    val expectedDutationPerSend = 1000.0 / nMessagesSentPerSecond
    nMessagesSent = 0
    var i = 0
    while (i < nSecondsToTestEachLoadState) {
      // usually we hope triggerChatMessages will be processed in extractly 1 second.
      val messageSendStartTime = System.currentTimeMillis
      val sendingRate = triggerChatMessages(nMessagesSentPerSecond)
      val duration = System.currentTimeMillis - messageSendStartTime
      val delta = expectedDutationPerSend - duration
      // TODO flow control here according to delta?
      nMessagesSent += nMessagesSentPerSecond
      overallEffectiveRate += sendingRate
      i += 1
    }
    isMessagesSent = true
    println(nMessagesSent + " msgs " + { if (isBroadcast) "broadcast" else "sent" } + " in " + (System.currentTimeMillis - t0) + "ms, expect to receive " + nMessagesExpected)

    overallEffectiveRate = overallEffectiveRate / nSecondsToTestEachLoadState
    //println("Rate: %.3f ".format(overallEffectiveRate))

    import system.dispatcher
    roundContext = RoundContext(
      Some(system.scheduler.scheduleOnce(postTestReceptionTimeout.seconds, self, ReceivingTimeout)),
      statistics, overallEffectiveRate)
  }

  private def goon(statistics: mutable.Map[Double, SummaryStatistics], overallEffectiveRate: Double) {
    statistics.put(overallEffectiveRate, processRoundtripStats)
    nMessagesSentPerSecond += nMessageSentPerSecondRamp

    if (!isConnectionLost && nMessagesSentPerSecond < maxMessagesSentPerSecond) {
      import system.dispatcher
      system.scheduler.scheduleOnce(secondsBetweenRounds.seconds) {
        triggerMessages(statistics, overallEffectiveRate)
      }
    } else {
      isTestRunning = false
      commander ! StatsSummary(statistics)
    }
  }

  /**
   * @return sending rate: nMessages / second
   */
  private def triggerChatMessages(totalMessages: Int): Double = {
    val t0 = System.currentTimeMillis

    var senders = clients
    var i = 0
    while (i < totalMessages) {
      senders.head ! SocketIOTestClient.SendTimestampedChat
      senders = senders.tail match {
        case Nil => clients
        case xs  => xs
      }
      i += 1
    }

    totalMessages / ((System.currentTimeMillis - t0) / 1000.0)
  }

  private def processRoundtripStats: SummaryStatistics = {
    val stats = new SummaryStatistics()

    roundtripTimes foreach stats.addValue
    println("     num      min    mean     max   stdev    rate")
    println("%8d   %6.0f  %6.0f  %6.0f  %6.0f   %5.0f\n".format(
      stats.getN, stats.getMin, stats.getMean, stats.getMax, stats.getStandardDeviation, stats.getN / (stats.getMean / 1000.0)))

    stats
  }
}
