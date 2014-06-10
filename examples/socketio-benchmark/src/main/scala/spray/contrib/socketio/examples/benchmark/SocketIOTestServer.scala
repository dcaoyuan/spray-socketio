package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ Terminated, ActorSystem, Actor, Props, ActorLogging, ActorRef }
import com.typesafe.config.ConfigFactory
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.SocketIOServerConnection
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.{ OnData, OnEvent }
import spray.contrib.socketio.namespace.NamespaceExtension
import spray.contrib.socketio.packet.EventPacket

object SocketIOTestServer extends App {

  final case object COUNT

  object SocketIOServer {
    def props(resolver: ActorRef) = Props(classOf[SocketIOServer], resolver)
  }

  class SocketIOServer(val resolver: ActorRef) extends Actor with ActorLogging {
    var connected = 0
    var preconnected = 0

    override def preStart() {
      import context.dispatcher
      context.system.scheduler.schedule(0 seconds, 5 seconds, self, COUNT)
    }

    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(SocketIOWorker.props(serverConnection, resolver))
        context watch conn
        connected += 1
        serverConnection ! Http.Register(conn)

      case COUNT =>
        if (preconnected != connected) {
          preconnected = connected
          log.info("connected: " + connected)
        }

      case Terminated(ref) =>
        log.info("terminated: " + ref)
        context unwatch ref
        connected -= 1
    }
  }

  object SocketIOWorker {
    def props(serverConnection: ActorRef, resolver: ActorRef) = Props(classOf[SocketIOWorker], serverConnection, resolver)
  }
  class SocketIOWorker(val serverConnection: ActorRef, val resolver: ActorRef) extends Actor with SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  SocketIOExtension(system)

  val observer = new Observer[OnData] with Serializable {
    implicit val resolverForNamescape = NamespaceExtension(system).resolver
    override def onNext(value: OnData) {
      value match {
        case OnEvent("chat", args, context) =>
          spray.json.JsonParser(args) // test spray-json performance too.
          if (isBroadcast) {
            value.broadcast("", EventPacket(-1L, false, value.endpoint, "chat", args))
          } else {
            value.replyEvent("chat", args)
          }
        case _ =>
          println("observed: " + value)
      }
    }
  }

  val channel = Subject[OnData]()
  // there is no channel.ofType method for RxScala, why?
  channel.subscribe(observer)

  NamespaceExtension(system).startNamespace("")
  NamespaceExtension(system).namespace("") ! Namespace.Subscribe(channel)

  val resolverForTransport = SocketIOExtension(system).resolver
  val server = system.actorOf(SocketIOServer.props(resolverForTransport), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("server.host")
  val port = config.getInt("server.port")
  val isBroadcast = config.getBoolean("broadcast")
  IO(UHttp) ! Http.Bind(server, host, port)
}
