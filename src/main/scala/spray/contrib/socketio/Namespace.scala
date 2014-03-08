package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import spray.contrib.socketio
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri
import spray.json.JsValue

/**
 *
 *
 *   [client1 events] [client2 events]  ... [clientN events]
 *          |               |                      |
 *          |               |                      |
 *          V               V                      V
 *   +-----------------------------------------------------+
 *   |                    endpoint                         |
 *   +-----------------------------------------------------+
 *               |                             |
 *               |                             |
 *               V                             V
 *         [namespace1]                  [namespace2]
 *               |
 *               |
 * (channel1)    +---> [************] -->
 * (channel2)    +---> [+++++++++] -->
 * (channelN)    +---> [$$$$$$$] -->
 *
 * @Note Akka can do millions of messages per second per actor per core.
 */
object Namespace {
  val DEFAULT_NAMESPACE = "socket.io"
  val NAMESPACES = "socketio-namespaces"

  final case class RemoveNamespace(namespace: String)
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transport: Transport)
  final case class OnPacket[T <: Packet](packet: T, connContext: ConnectionContext)
  final case class Subscribe[T <: OnData](system: ActorSystem, endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def endpoint: String

    def replyMessage(msg: String) = context.connectionActive ! ConnectionActive.SendMessage(msg, endpoint)
    def replyJson(json: JsValue) = context.connectionActive ! ConnectionActive.SendJson(json, endpoint)
    def replyEvent(name: String, args: JsValue*) = context.connectionActive ! ConnectionActive.SendEvent(name, args.toList, endpoint)
    def reply(packets: Packet*) = context.connectionActive ! ConnectionActive.SendPackets(packets)
    def broadcast(packet: Packet) {} //TODO
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: JsValue, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], context: ConnectionContext)(implicit val endpoint: String) extends OnData

  def subscribe[T <: OnData: TypeTag](endpoint: String, observer: Observer[T])(system: ActorSystem) {
    tryDispatch(system, endpoint, Subscribe(system, endpoint, observer))
  }

  def actorPath(namespace: String) = "/user/" + namespace

  def tryDispatch(system: ActorSystem, endpoint: String, msg: Any) {
    val namespace = if (endpoint == "") DEFAULT_NAMESPACE else endpoint
    import system.dispatcher
    system.actorSelection(actorPath(namespace)).resolveOne(5.seconds).recover {
      case _: Throwable => system.actorOf(Props(classOf[Namespace], namespace), name = namespace)
    } map (_ ! msg)
  }

  def dispatch(system: ActorSystem, endpoint: String, msg: Any) {
    val namespace = if (endpoint == "") DEFAULT_NAMESPACE else endpoint
    import system.dispatcher
    system.actorSelection(actorPath(namespace)) ! msg
  }

}

/**
 * Namespace is refered to endpoint fo packets
 */
class Namespace(implicit val endpoint: String) extends Actor with ActorLogging {
  import Namespace._

  private val connections = new TrieMap[String, ConnectionContext]()

  val connectChannel = Subject[OnConnect]()
  val disconnectChannel = Subject[OnDisconnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, connContext)    => connectChannel.onNext(OnConnect(packet.args, connContext))
    case OnPacket(packet: DisconnectPacket, connContext) => disconnectChannel.onNext(OnDisconnect(connContext))
    case OnPacket(packet: MessagePacket, connContext)    => messageChannel.onNext(OnMessage(packet.data, connContext))
    case OnPacket(packet: JsonPacket, connContext)       => jsonChannel.onNext(OnJson(packet.json, connContext))
    case OnPacket(packet: EventPacket, connContext)      => eventChannel.onNext(OnEvent(packet.name, packet.args, connContext))

    case x @ Subscribe(_, _, observer) =>
      x.tag.tpe match {
        case t if t =:= typeOf[OnConnect]    => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
        case t if t =:= typeOf[OnDisconnect] => disconnectChannel(observer.asInstanceOf[Observer[OnDisconnect]])
        case t if t =:= typeOf[OnMessage]    => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
        case t if t =:= typeOf[OnJson]       => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
        case t if t =:= typeOf[OnEvent]      => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
        case _                               =>
      }

    case Broadcast(packet) =>
      gossip(packet)

  }

  def gossip(packet: Packet) {
    //connections foreach (_._2.transport.sendPacket(packet))
  }

}
