package spray.contrib.socketio.examples.benchmark

import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.WatermarkRequestStrategy
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ ActorSystem, Props }
import akka.io.IO
import akka.persistence.Persistence
//import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import spray.can.server.UHttp
import spray.can.Http
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.examples.benchmark.SocketIOTestServer.SocketIOServer
import spray.contrib.socketio.namespace.Channel
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.namespace.NamespaceExtension
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.json.JsArray
import spray.json.JsString

object SocketIOTestClusterServer extends App {
  val usage = """
    Usage: SocketIOTestClusterServer [transport|session|business] -Dakka.cluster.seed-nodes.0=akka.tcp://ClusterSystem@host1:port -Dakka.remote.netty.tcp.hostname=host1 -Dakka.remote.netty.tcp.port=port
              """

  def exitWithUsage = {
    println(usage)
    sys.exit(1)
  }

  def startCluster(config: Config): ActorSystem = {
    val system = ActorSystem("ClusterSystem", config)
    SocketIOExtension(system)
    system
  }

  if (args.length == 0) {
    exitWithUsage
  }
  val arglist = args.toList

  import ConfigFactory._

  val commonSettings = load()

  implicit var system: ActorSystem = _

  arglist match {
    case "transport" :: tail =>
      val config = parseString("akka.cluster.roles =[\"transport\"]").withFallback(commonSettings)
      system = startCluster(config)

      implicit val sessionRegion = SocketIOExtension(system).sessionRegion
      val server = system.actorOf(SocketIOServer.props(sessionRegion), name = "socketio-server")
      val host = config.getString("transport.hostname")
      val port = config.getInt("transport.port")
      IO(UHttp) ! Http.Bind(server, host, port)

    case "session" :: tail =>
      val config = parseString("akka.cluster.roles =[\"connectionSession\"]").withFallback(commonSettings)
      system = startCluster(config)
      Persistence(system)
    //val sharedStore = system.actorOf(Props[SharedLeveldbStore], "store")
    //SharedLeveldbJournal.setStore(sharedStore, system)

    case "business" :: tail =>
      val config = parseString("akka.cluster.roles =[\"business\"]").withFallback(commonSettings)
      system = ActorSystem("NamespaceSystem", config)

      val appConfig = load()
      val isBroadcast = appConfig.getBoolean("spray.socketio.benchmark.broadcast")
      class Receiver extends ActorSubscriber {
        implicit val sessionClient = SocketIOExtension(system).sessionClient
        override val requestStrategy = WatermarkRequestStrategy(10)

        def receive = {
          case OnNext(value @ OnEvent("chat", args, context)) => // for spec and load test
            spray.json.JsonParser(args) // test spray-json too.
            //println("on chat event")
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

      val channel = system.actorOf(Channel.props())
      val receiver = system.actorOf(Props(new Receiver))
      ActorPublisher(channel).subscribe(ActorSubscriber(receiver))

      NamespaceExtension(system).startNamespace("")
      NamespaceExtension(system).namespace("") ! Namespace.Subscribe("", channel)

    case _ =>
      exitWithUsage
  }
}
