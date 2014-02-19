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

  private val allSoContexts = new mutable.WeakHashMap[ActorRef, SocketIOContext]()
  private val connectionActivities = new mutable.WeakHashMap[ActorRef, ActorRef]()

  final case class Remove(namespace: String)
  final case class Connected(soContext: SocketIOContext)
  final case class OnPacket[T <: Packet](packet: T, socket: ActorRef)
  final case class Subscribe[T <: OnData](endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  // --- Observable data
  sealed trait OnData {
    def conn: SocketIOContext
    implicit def endpoint: String

    def replyMessage(message: String) = conn.sendMessage(message)
    def replyJson(json: JsValue) = conn.sendJson(json)
    def replyEvent(name: String, args: List[JsValue]) = conn.sendEvent(name, args)
    def reply(packet: Packet) = conn.send(packet)
  }
  final case class OnConnect(args: Seq[(String, String)], conn: SocketIOContext)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, conn: SocketIOContext)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: JsValue, conn: SocketIOContext)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], conn: SocketIOContext)(implicit val endpoint: String) extends OnData

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

      case x @ Connected(soContext @ SocketIOContext(_, _, connActor)) =>
        context.watch(connActor)
        allSoContexts += (connActor -> soContext)
        connectionActivities += (connActor -> context.actorOf(Props(classOf[ConnectionActivity], soContext)))

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
        log.info("clients removed: {}", allSoContexts)
        allSoContexts -= connActor
        connectionActivities -= connActor
    }
  }

}

/**
 * Namespace is refered to endpoint fo packets
 */
class Namespace(implicit val endpoint: String) extends Actor with ActorLogging {
  import Namespace._

  private val soContexts = new mutable.WeakHashMap[ActorRef, SocketIOContext]()

  val connectChannel = Subject[OnConnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, connActor) =>
      context.watch(connActor)
      allSoContexts.get(connActor) foreach { soContext => soContexts += (connActor -> soContext) }
      soContexts.get(connActor) foreach { soContext => connectChannel.onNext(OnConnect(packet.args, soContext)) }
      log.info("clients for {}: {}", endpoint, soContexts)

    case OnPacket(packet: DisconnectPacket, connActor) =>
      soContexts -= connActor
      log.info("clients removed: {}", soContexts)

    case OnPacket(HeartbeatPacket, connActor) =>
      soContexts -= connActor
      log.info("clients removed: {}", soContexts)

    case OnPacket(packet: MessagePacket, connActor) => soContexts.get(connActor) foreach { soContext => messageChannel.onNext(OnMessage(packet.data, soContext)) }
    case OnPacket(packet: JsonPacket, connActor)    => soContexts.get(connActor) foreach { soContext => jsonChannel.onNext(OnJson(packet.json, soContext)) }
    case OnPacket(packet: EventPacket, connActor)   => soContexts.get(connActor) foreach { soContext => eventChannel.onNext(OnEvent(packet.name, packet.args, soContext)) }

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
      log.info("clients removed: {}", soContexts)
      soContexts -= connActor
  }

  def gossip(packet: Packet) {
    soContexts foreach (_._2.send(packet))
  }

}
