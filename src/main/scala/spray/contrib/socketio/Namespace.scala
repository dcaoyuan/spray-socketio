package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Terminated
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.collection.mutable
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import scala.util.Failure
import scala.util.Success
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.ConnectionActivity.ReclockCloseTimeout
import spray.contrib.socketio.ConnectionActivity.ReclockHeartbeatTimeout
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.json.JsValue

object Namespace {
  val DEFAULT_NAMESPACE = "socket.io"

  private val allConnections = new mutable.WeakHashMap[ActorRef, SocketIOConnection]()
  private val connectionActivities = new mutable.WeakHashMap[ActorRef, ActorRef]()

  final case class Remove(namespace: String)
  final case class Connected(connection: SocketIOConnection)
  final case class OnPacket[T <: Packet](packet: T, socket: ActorRef)
  final case class Subscribe[T <: OnData](endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  // --- Observable data
  sealed trait OnData {
    def conn: SocketIOConnection
    implicit def endpoint: String

    def replyMessage(message: String) = conn.sendMessage(message)
    def replyJson(json: JsValue) = conn.sendJson(json)
    def replyEvent(name: String, args: List[JsValue]) = conn.sendEvent(name, args)
    def reply(packet: Packet) = conn.send(packet)
  }
  final case class OnConnect(args: Seq[(String, String)], conn: SocketIOConnection)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, conn: SocketIOConnection)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: JsValue, conn: SocketIOConnection)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], conn: SocketIOConnection)(implicit val endpoint: String) extends OnData

  class Namespaces extends Actor with ActorLogging {
    import context.dispatcher

    def toName(endpoint: String) = if (endpoint == "") DEFAULT_NAMESPACE else endpoint

    def tryNamespace(name: String, msg: Option[Any]) {
      context.actorSelection(name).resolveOne(5.seconds).onComplete {
        case Success(ns) => msg foreach (ns.tell(_, self))
        case Failure(_) =>
          val ns = context.actorOf(Props(classOf[Namespace], name), name = name)
          msg foreach (ns.tell(_, self))
      }
    }

    def receive: Receive = {
      case x @ Subscribe(endpoint, observer) =>
        tryNamespace(toName(endpoint), Some(x))

      case x @ Connected(conn @ SocketIOConnection(_, _, connActor)) =>
        context.watch(connActor)
        allConnections += (connActor -> conn)
        connectionActivities += (connActor -> context.actorOf(Props(classOf[ConnectionActivity], conn)))

      case x @ OnPacket(packet @ ConnectPacket(endpoint, args), connActor) =>
        // do authorization here ?
        connActor ! TextFrame(packet.render)

        tryNamespace(toName(endpoint), Some(x))

      case x @ OnPacket(packet @ DisconnectPacket(endpoint), connActor) =>
        tryNamespace(toName(endpoint), Some(x)) // TODO

      case x @ OnPacket(HeartbeatPacket, connActor) =>
        connectionActivities.get(connActor) foreach { _ ! ReclockHeartbeatTimeout }

      case x @ OnPacket(packet, connActor) =>
        val name = toName(packet.endpoint)
        context.actorSelection(name) ! x

      case Remove(namespace) =>
        val ns = context.actorSelection(namespace)
        ns ! Broadcast(DisconnectPacket(namespace))
        ns ! PoisonPill

      case x @ Broadcast(packet) =>
        val name = toName(packet.endpoint)
        context.actorSelection(name) ! x

      case Terminated(connActor) =>
        log.info("clients removed: {}", allConnections)
        allConnections -= connActor
        connectionActivities -= connActor
    }
  }

}

/**
 * Namespace is refered to endpoint fo packets
 */
class Namespace(implicit val endpoint: String) extends Actor with ActorLogging {
  import Namespace._

  private val connections = new mutable.WeakHashMap[ActorRef, SocketIOConnection]()

  val connectChannel = Subject[OnConnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, connActor) =>
      context.watch(connActor)
      allConnections.get(connActor) foreach { conn => connections += (connActor -> conn) }
      connections.get(connActor) foreach { conn => connectChannel.onNext(OnConnect(packet.args, conn)) }
      log.info("clients for {}: {}", endpoint, connections)

    case OnPacket(packet: DisconnectPacket, connActor) =>
      connections -= connActor
      log.info("clients removed: {}", connections)

    case OnPacket(HeartbeatPacket, connActor) =>
      connections -= connActor
      log.info("clients removed: {}", connections)

    case OnPacket(packet: MessagePacket, connActor) => connections.get(connActor) foreach { conn => messageChannel.onNext(OnMessage(packet.data, conn)) }
    case OnPacket(packet: JsonPacket, connActor)    => connections.get(connActor) foreach { conn => jsonChannel.onNext(OnJson(packet.json, conn)) }
    case OnPacket(packet: EventPacket, connActor)   => connections.get(connActor) foreach { conn => eventChannel.onNext(OnEvent(packet.name, packet.args, conn)) }

    case x @ Subscribe(_, observer) =>
      x.tag.tpe match {
        case t if t =:= typeOf[OnConnect] => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
        case t if t =:= typeOf[OnMessage] => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
        case t if t =:= typeOf[OnJson]    => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
        case t if t =:= typeOf[OnEvent]   => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
        case _                            =>
      }

    case Broadcast(packet) => gossip(packet)

    case Terminated(connActor) =>
      log.info("clients removed: {}", connections)
      connections -= connActor
  }

  def gossip(packet: Packet) {
    connections foreach (_._2.send(packet))
  }

}
