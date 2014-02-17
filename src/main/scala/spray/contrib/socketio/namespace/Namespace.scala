package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.collection.mutable
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import scala.util.Failure
import scala.util.Success
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.SocketIOContext
import spray.contrib.socketio.SocketIOSocket
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketRender
import spray.json.JsValue

object Namespace {
  val DEFAULT_NAMESPACE = "socket.io"

  private val allConnections = new mutable.WeakHashMap[ActorRef, SocketIOContext]()

  final case class Remove(namespace: String)
  final case class Connected(connection: SocketIOSocket)
  final case class OnPacket[T <: Packet](packet: T, socket: ActorRef)
  final case class Subscribe[T <: OnData](endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  // --- Observable data
  trait OnData
  final case class OnConnect(args: Seq[(String, String)], socket: SocketIOSocket) extends OnData
  final case class OnMessage(msg: String, socket: SocketIOSocket) extends OnData
  final case class OnJson(json: JsValue, socket: SocketIOSocket) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], socket: SocketIOSocket) extends OnData

  class Namespaces extends Actor with ActorLogging {
    import context.dispatcher

    private def toName(endpoint: String) = if (endpoint == "") DEFAULT_NAMESPACE else endpoint

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

      case x @ Connected(SocketIOSocket(_, sender, context)) =>
        allConnections += (sender -> context)

      case x @ OnPacket(packet @ ConnectPacket(endpoint, args), sender) =>
        // do authorization here ?
        sender ! TextFrame(PacketRender.render(packet))

        tryNamespace(toName(endpoint), Some(x))

      case x @ OnPacket(packet @ DisconnectPacket(endpoint), sender) =>
        tryNamespace(toName(endpoint), Some(x)) // TODO

      case x @ OnPacket(packet, sender) =>
        val name = toName(packet.endpoint)
        context.actorSelection(name) ! x

      case Remove(namespace) =>
        val ns = context.actorSelection(namespace)
        ns ! Broadcast(DisconnectPacket(namespace))
        ns ! PoisonPill

      case x @ Broadcast(packet) =>
        val name = toName(packet.endpoint)
        context.actorSelection(name) ! x

    }
  }

}

/**
 * Namespace is refered to endpoint fo packets
 */
class Namespace private (val endpoint: String) extends Actor with ActorLogging {
  import Namespace._

  log.info("namespace created {}, path {}", endpoint, self.path)
  private val sockets = new mutable.WeakHashMap[ActorRef, SocketIOContext]()

  val connectChannel = Subject[OnConnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def socketFor(sender: ActorRef): Option[SocketIOSocket] = sockets.get(sender) map (SocketIOSocket(endpoint, sender, _))
  def sessionIdFor(sender: ActorRef): Option[String] = sockets.get(sender) map (_.sessionId)

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, sender) =>
      allConnections.get(sender) foreach { context => sockets += (sender -> context) }
      socketFor(sender) foreach { socket => connectChannel.onNext(OnConnect(packet.args, socket)) }
      log.info("clients for {}: {}", endpoint, sockets)

    case OnPacket(packet: DisconnectPacket, sender) =>
      sockets -= sender
      log.info("clients removed: {}", sockets)

    case OnPacket(packet: MessagePacket, sender) => socketFor(sender) foreach { socket => messageChannel.onNext(OnMessage(packet.data, socket)) }
    case OnPacket(packet: JsonPacket, sender)    => socketFor(sender) foreach { socket => jsonChannel.onNext(OnJson(packet.json, socket)) }
    case OnPacket(packet: EventPacket, sender)   => socketFor(sender) foreach { socket => eventChannel.onNext(OnEvent(packet.name, packet.args, socket)) }

    case x @ Subscribe(_, observer) =>
      x.tag.tpe match {
        case t if t =:= typeOf[OnConnect] => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
        case t if t =:= typeOf[OnMessage] => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
        case t if t =:= typeOf[OnJson]    => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
        case t if t =:= typeOf[OnEvent]   => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
        case _                            =>
      }

    case Broadcast(packet) => gossip(TextFrame(PacketRender.render(packet)))
  }

  protected def gossip(msg: Any) {
    sockets foreach (_._1 ! msg)
  }

}
