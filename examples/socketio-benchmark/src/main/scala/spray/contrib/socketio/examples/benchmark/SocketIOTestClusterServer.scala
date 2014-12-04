package spray.contrib.socketio.examples.benchmark

import akka.actor.{ ActorSystem, Props }
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.io.IO
import akka.persistence.Persistence
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.WatermarkRequestStrategy
import com.typesafe.config.{ Config, ConfigFactory }
import spray.can.server.UHttp
import spray.can.Http
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionSession
import spray.contrib.socketio.ConnectionSession.OnEvent
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.examples.benchmark.SocketIOTestServer.SocketIOServer
import spray.contrib.socketio.mq.Queue
import spray.contrib.socketio.mq.Topic
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.json.JsArray
import spray.json.JsString

object SocketIOTestClusterServer extends App {
  val usage =
    """
      Usage: SocketIOTestClusterServer [session|topic|transport|business] -Dakka.cluster.seed-nodes.0=akka.tcp://SocketIOSystem@host1:port -Dakka.remote.netty.tcp.hostname=host -Dakka.remote.netty.tcp.port=port
    """

  def exitWithUsage = {
    println(usage)
    sys.exit(1)
  }

  def socketioSystem(config: Config) = {
    ActorSystem("SocketIOSystem", config)
  }

  def businessSystem(config: Config) = {
    ActorSystem("BusinessSystem", config)
  }

  if (args.length == 0) {
    exitWithUsage
  }
  val arglist = args.toList

  val commonConfig = ConfigFactory.load()

  arglist match {
    case "session" :: tail =>
      val extraCfg =
        """
          akka.contrib.cluster.sharding.role = "session"
          akka.cluster.roles = ["session", "topic"]
        """
      val config = ConfigFactory.parseString(extraCfg).withFallback(commonConfig)

      val system = socketioSystem(config)
      Persistence(system)

      Topic.startTopicAggregator(system, role = Some("topic"))
      Topic.startSharding(system, None)
      ConnectionSession.startSharding(system, Some(SocketIOExtension(system).sessionProps))

    case "topic" :: tail =>
      val extraCfg =
        """
          akka.contrib.cluster.sharding.role = "topic"
          akka.cluster.roles = ["topic"]
        """
      val config = ConfigFactory.parseString(extraCfg).withFallback(commonConfig)

      val system = socketioSystem(config)
      Persistence(system)

      Topic.startTopicAggregator(system, Some("topic"))
      // should start the proxy too, since topics should report to topicAggregator via this proxy
      Topic.startTopicAggregatorProxy(system, Some("topic"))
      Topic.startSharding(system, Some(SocketIOExtension(system).topicProps))

    case "transport" :: tail =>
      val extraCfg =
        """
          akka.cluster.roles =["transport"]
        """
      val config = ConfigFactory.parseString(extraCfg).withFallback(commonConfig)

      implicit val system = socketioSystem(config)
      ConnectionSession.startSharding(system, None)

      val server = system.actorOf(SocketIOServer.props(), name = "socketio-server")
      val host = config.getString("transport.host")
      val port = config.getInt("transport.port")
      IO(UHttp) ! Http.Bind(server, host, port)

    case "business" :: tail =>
      val extraCfg =
        """
          akka.cluster.roles =["business"]
        """
      val config = ConfigFactory.parseString(extraCfg).withFallback(commonConfig)

      implicit val system = businessSystem(config)
      val socketioExt = SocketIOExtension(system)

      val appConfig = ConfigFactory.load()
      val isBroadcast = appConfig.getBoolean("spray.socketio.benchmark.broadcast")

      class Receiver extends ActorSubscriber {
        override val requestStrategy = WatermarkRequestStrategy(10)

        implicit val sessionClient = socketioExt.sessionClient
        def receive = {
          case OnNext(value @ OnEvent("chat", args, context)) => // for spec and load test
            spray.json.JsonParser(args) // test spray-json too.
            if (isBroadcast) {
              value.broadcast("", EventPacket(-1L, false, value.endpoint, "chat", args))
            } else {
              value.replyEvent("chat", args)
            }

          case OnNext(value @ OnEvent("broadcast", args, context)) => // for spec test
            val msg = spray.json.JsonParser(args).asInstanceOf[JsArray].elements.head.asInstanceOf[JsString].value
            value.broadcast("", MessagePacket(-1, false, value.endpoint, msg))

          case _ =>
        }
      }

      val queue = system.actorOf(Queue.props())
      val receiver = system.actorOf(Props(new Receiver))
      ActorPublisher(queue).subscribe(ActorSubscriber(receiver))

      val topicClient = socketioExt.topicClient
      topicClient ! Subscribe(Topic.EMPTY, queue)

    case _ =>
      exitWithUsage
  }
}
