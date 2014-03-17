package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Props }
import akka.pattern.ask
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import com.typesafe.config.ConfigFactory
import rx.lang.scala.Observer
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.contrib.socketio
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.examples.benchmark.SocketIOTestServer.SocketIOServer
import spray.json.JsArray
import spray.json.JsString

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
      |spray.socketio.mode="cluster""").withFallback(ConfigFactory.load())
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
          val msg = spray.json.JsonParser(args).asInstanceOf[JsArray].elements.head.asInstanceOf[JsString].value
          next.broadcast("", MessagePacket(-1, false, next.endpoint, msg))
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  // start the Persistence extension
  Persistence(system)
  startupSharedJournal(system)

  socketioExt.startNamespace("")
  socketioExt.namespace("") ! Namespace.Subscribe(observer)

  val server = system.actorOf(Props(classOf[SocketIOServer], resolver), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  IO(UHttp) ! Http.Bind(server, host, port)
}
