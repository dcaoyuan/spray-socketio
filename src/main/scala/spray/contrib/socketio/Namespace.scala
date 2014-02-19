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
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.SocketIOConnection.ReclockCloseTimeout
import spray.contrib.socketio.SocketIOConnection.ReclockHeartbeatTimeout
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

  private val allConnections = new mutable.HashMap[ActorRef, ActorRef]() // transportActor -> SocketIOConnection_Actor

  final case class Remove(namespace: String)
  final case class Connected(soContext: SocketIOContext)
  final case class OnPacket[T <: Packet](packet: T, socket: ActorRef)
  final case class Subscribe[T <: OnData](endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  // --- Observable data
  sealed trait OnData {
    import spray.contrib.socketio.SocketIOConnection._

    def conn: ActorRef // SocketIOConnection Actor
    implicit def endpoint: String

    def replyMessage(msg: String) = conn ! ReplyMessage(msg)
    def replyJson(json: JsValue) = conn ! ReplyJson(json)
    def replyEvent(name: String, args: List[JsValue]) = conn ! ReplyEvent(name, args)
    def reply(packet: Packet) = conn ! Reply(packet)
  }
  final case class OnConnect(args: Seq[(String, String)], conn: ActorRef)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, conn: ActorRef)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: JsValue, conn: ActorRef)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], conn: ActorRef)(implicit val endpoint: String) extends OnData

  class Namespaces extends Actor with ActorLogging {
    import context.dispatcher

    def toName(endpoint: String) = if (endpoint == "") DEFAULT_NAMESPACE else endpoint

    def tryDispatch(namespace: String, msg: Any) {
      for {
        ns <- context.actorSelection(namespace).resolveOne(5.seconds).recover {
          case _: Throwable => context.actorOf(Props(classOf[Namespace], namespace), name = namespace)
        }
      } ns ! msg
    }

    def receive: Receive = {
      case x @ Subscribe(endpoint, observer) =>
        tryDispatch(toName(endpoint), x)

      case x @ Connected(soContext: SocketIOContext) =>
        context.watch(soContext.transportActor)
        allConnections += (soContext.transportActor -> context.actorOf(Props(classOf[SocketIOConnection], soContext)))

      case x @ OnPacket(packet @ ConnectPacket(endpoint, args), transportActor) =>
        // do authorization here ?
        transportActor ! TextFrame(packet.render)
        tryDispatch(toName(endpoint), x)

      case x @ OnPacket(packet @ DisconnectPacket(endpoint), transportActor) =>
        context.actorSelection(toName(endpoint)) ! x

      case x @ OnPacket(HeartbeatPacket, transportActor) =>
        allConnections.get(transportActor) foreach { _ ! ReclockHeartbeatTimeout }

      case x @ OnPacket(packet, transportActor) =>
        context.actorSelection(toName(packet.endpoint)) ! x

      case Remove(namespace) =>
        val ns = context.actorSelection(namespace)
        ns ! Broadcast(DisconnectPacket(namespace))
        ns ! PoisonPill

      case x @ Broadcast(packet) =>
        context.actorSelection(toName(packet.endpoint)) ! x

      case Terminated(transportActor) =>
        allConnections.get(transportActor) foreach { _ ! ReclockCloseTimeout } // TODO
        log.info("clients removed: {}", allConnections)
        allConnections -= transportActor
    }
  }

}

/**
 * Namespace is refered to endpoint fo packets
 */
class Namespace(implicit val endpoint: String) extends Actor with ActorLogging {
  import Namespace._

  private val connections = new mutable.HashMap[ActorRef, ActorRef]()

  val connectChannel = Subject[OnConnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, transportActor) =>
      context.watch(transportActor)
      allConnections.get(transportActor) foreach { conn => connections += (transportActor -> conn) }
      connections.get(transportActor) foreach { conn => connectChannel.onNext(OnConnect(packet.args, conn)) }
      log.info("clients for {}: {}", endpoint, connections)

    case OnPacket(packet: DisconnectPacket, transportActor) =>
      connections -= transportActor
      log.info("clients removed: {}", connections)

    case OnPacket(HeartbeatPacket, transportActor) =>
      connections -= transportActor
      log.info("clients removed: {}", connections)

    case OnPacket(packet: MessagePacket, transportActor) => connections.get(transportActor) foreach { conn => messageChannel.onNext(OnMessage(packet.data, conn)) }
    case OnPacket(packet: JsonPacket, transportActor)    => connections.get(transportActor) foreach { conn => jsonChannel.onNext(OnJson(packet.json, conn)) }
    case OnPacket(packet: EventPacket, transportActor)   => connections.get(transportActor) foreach { conn => eventChannel.onNext(OnEvent(packet.name, packet.args, conn)) }

    case x @ Subscribe(_, observer) =>
      x.tag.tpe match {
        case t if t =:= typeOf[OnConnect] => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
        case t if t =:= typeOf[OnMessage] => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
        case t if t =:= typeOf[OnJson]    => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
        case t if t =:= typeOf[OnEvent]   => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
        case _                            =>
      }

    case Broadcast(packet) => gossip(packet)

    case Terminated(transportActor) =>
      log.info("clients removed: {}", connections)
      connections -= transportActor
  }

  def gossip(packet: Packet) {
    connections foreach (_._2 ! spray.contrib.socketio.SocketIOConnection.Reply(packet))
  }

}
