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
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.ClusterConnectionActive
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.examples.benchmark.SocketIOTestServer.SocketIOServer
import spray.contrib.socketio.extension.SocketIOExtension

object SocketIOClusterTestServer extends App {

  def startupSharedJournal(system: ActorSystem) {
    val store = system.actorOf(Props[SharedLeveldbStore], "store")
    SharedLeveldbJournal.setStore(store, system)
  }

  val clusterPort = 2551
  // Override the configuration of the port
  val systemConfig = ConfigFactory.parseString(
    """
      |akka.remote.netty.tcp.port=2551
      |spray.socketio.mode="cluster"""").withFallback(ConfigFactory.load())
  implicit val system = ActorSystem("ClusterSystem", systemConfig)
  val socketioExt = SocketIOExtension(system)
  implicit val resolver = socketioExt.resolver

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          spray.json.JsonParser(args) // test spray-json performance too.
          next.replyEvent("chat", args)
        case OnEvent("broadcast", args, context) =>
          next.broadcast(next.endpoint, MessagePacket(0, false, "", args))
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  // start the Persistence extension
  Persistence(system)
  startupSharedJournal(system)

  socketioExt.startNamespace()
  Namespace.subscribe(observer)(socketioExt.namespace())

  val server = system.actorOf(Props(classOf[SocketIOServer], resolver), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  IO(UHttp) ! Http.Bind(server, host, port)
}
