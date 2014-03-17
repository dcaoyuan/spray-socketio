package spray.contrib.socketio.examples

import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ Props, ActorSystem }
import spray.contrib.socketio.{ ClusterConnectionActive, ConnectionActive }
import akka.contrib.pattern.{ DistributedPubSubExtension, ClusterSharding }
import akka.cluster.Cluster
import akka.persistence.Persistence
import spray.contrib.socketio.examples.benchmark.SocketIOTestServer.SocketIOServer
import akka.io.IO
import spray.can.server.UHttp
import spray.can.Http
import rx.lang.scala.Observer
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.namespace.Namespace
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import spray.contrib.socketio.extension.SocketIOExtension

object SimpleClusterServer extends App with MySslConfiguration {
  val usage = """
    Usage: SimpleClusterServer [transport|connectionActive|business] -Dakka.cluster.seed-nodes.0=akka.tcp://ClusterSystem@host1:port -Dakka.remote.netty.tcp.hostname=host1 -Dakka.remote.netty.tcp.port=port
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

  val commonSettings = load(ConfigFactory.parseString(
    """
      |akka.loglevel = INFO
      |akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
      |akka.persistence.journal.leveldb-shared.store {
      |  native = off
      |  dir = "target/test-shared-journal"
      |}
      |akka.persistence.snapshot-store.local.dir = "target/test-snapshots"
      |akka.contrib.cluster.sharding.role = "connectionActive"
      |transport.hostname = "0.0.0.0"
      |transport.port = 8080
      |spray.socketio.mode = "cluster"
    """.stripMargin))

  implicit var system: ActorSystem = _

  arglist match {
    case "transport" :: tail =>
      val config = parseString("akka.cluster.roles =[\"transport\"]").withFallback(commonSettings)
      system = startCluster(config)

      implicit val resolver = ClusterSharding(system).shardRegion(ConnectionActive.shardName)
      val server = system.actorOf(Props(classOf[SocketIOServer], resolver), name = "socketio-server")
      val host = config.getString("transport.hostname")
      val port = config.getInt("transport.port")
      IO(UHttp) ! Http.Bind(server, host, port)

    case "connectionActive" :: tail =>
      val config = parseString("akka.cluster.roles =[\"connectionActive\"]").withFallback(commonSettings)
      system = startCluster(config)
      Persistence(system)
      val sharedStore = system.actorOf(Props[SharedLeveldbStore], "store")
      SharedLeveldbJournal.setStore(sharedStore, system)

    case "business" :: tail =>
      val config = parseString("akka.cluster.roles =[\"business\"]").withFallback(commonSettings)
      system = startCluster(config)
      val socketioExt = SocketIOExtension(system)
      implicit val resolver = socketioExt.resolver

      val observer = Observer[OnEvent](
        (next: OnEvent) => {
          next match {
            case OnEvent("chat", args, context) =>
              spray.json.JsonParser(args) // test spray-json too.
              next.replyEvent("chat", args)
            case OnEvent("broadcast", args, context) =>
              next.broadcast("", MessagePacket(0, false, "", args))
            case _ =>
              println("observed: " + next.name + ", " + next.args)
          }
        })

      socketioExt.startNamespace()
      Namespace.subscribe(observer)(socketioExt.namespace())

    case _ =>
      exitWithUsage
  }

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()

}
