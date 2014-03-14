package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef, ActorIdentity, Identify }
import akka.contrib.pattern.{ ClusterSharding }
import akka.cluster.Cluster
import akka.pattern.ask
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import rx.lang.scala.Observer
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio
import spray.contrib.socketio.{ ConnectionActive, SocketIOServerConnection }
import spray.contrib.socketio.namespace.ClusterNamespace
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.ClusterConnectionActive
import spray.contrib.socketio.packet.MessagePacket

object SocketIOClusterTestServer extends App {

  class SocketIOServer(resolver: ActorRef) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, resolver))
        serverConnection ! Http.Register(conn)
    }
  }

  class SocketIOWorker(val serverConnection: ActorRef, val resolver: ActorRef) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  def startupSharedJournal(system: ActorSystem, startStore: Boolean, name: String, path: String) {
    // Start the shared journal on one node (don't crash this SPOF)
    // This will not be needed with a distributed journal
    if (startStore) {
      system.actorOf(Props[SharedLeveldbStore], name)
    }
    // register the shared journal
    import system.dispatcher
    implicit val timeout = Timeout(1.minute)
    val f = (system.actorSelection(path) ? Identify(None))
    f.onSuccess {
      case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
      case _ =>
        system.log.error("Shared journal not started at {}", path)
        system.shutdown()
    }
    f.onFailure {
      case _ =>
        system.log.error("Lookup of shared journal at {} timed out", path)
        system.shutdown()
    }
  }

  val clusterPort = 2551
  // Override the configuration of the port
  val systemConfig = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + clusterPort).withFallback(ConfigFactory.load())
  implicit val system = ActorSystem("ClusterSystem", systemConfig)
  implicit val resolver = ClusterSharding(system).shardRegion(ConnectionActive.shardName)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          spray.json.JsonParser(args) // test spray-json performance too.
          next.replyEvent("chat", args)
        case OnEvent("broadcast", args, context) =>
          //FIXME how to get endpoint in observer?
          next.broadcast("", MessagePacket(0, false, "", args))
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  import system.dispatcher
  val clusterSystem = Cluster(system)

  // start the Persistence extension
  Persistence(system)
  startupSharedJournal(system, clusterSystem.selfAddress.port == Some(clusterPort), "store", clusterSystem.selfAddress + "/user/store")

  // join to cluster
  clusterSystem.join(clusterSystem.selfAddress)

  ClusterSharding(system).start(
    typeName = ConnectionActive.shardName,
    entryProps = Some(Props(classOf[ClusterConnectionActive])),
    idExtractor = ClusterConnectionActive.idExtractor,
    shardResolver = ClusterConnectionActive.shardResolver)

  Namespace.subscribe("", observer)(system, Props(classOf[ClusterNamespace], ""))
  val server = system.actorOf(Props(classOf[SocketIOServer], resolver), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  IO(UHttp) ! Http.Bind(server, host, port)
}
