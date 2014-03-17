package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.pattern.ask
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.reflect.runtime.universe._
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionActive
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DataPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import scala.util.{Failure, Success}

/**
 *
 *
 *    +======node======+
 *    |       mediator----\
 *    |     /     |    |  |
 *    |    /      |    |  |
 *    | conn    conn   |  |
 *    | conn    conn   |  |---------------------------------------------------+
 *    |                |  |                  virtaul MEDIATOR                 |
 *    +================+  |---------------------------------------------------+
 *                        |       |
 *                        |       | (Namespace)
 *    +======node======+  |     +=V=============busi-node===============+
 *    |       mediator----/     | +endpointA              [busi-actors] |
 *    |     /     |    |        |   |  |   |                            |
 *    |    /      |    |        |   |  |   +roomA                       |
 *    | conn    conn   |        |   |  |      |                         |
 *    | conn    conn   |        |   |  |      |                         |
 *    | /              |        |   |  |      \---> channelA            |
 *    +=|==============+        |   |  |      \---> channleB            |
 *      |                       |   |  |                                |
 *      \                       |   |  +roomB                           |
 *       \                      |   |     |                             |
 *    +---|-------------+       |   |     |                             |
 *    |   | resolver    |       |   |     \---> channelA --> [observer]-----\
 *    +---|-------------+       |   |     \---> channelB                |   |
 *        |                     |   |                                   |   |
 *        |                     |   \---> channelA                      |   |
 *        |                     |   \---> channelB                      |   |
 *        |                     +=======================================+   |
 *        |                                                                 |
 *        |                                                                 |
 *        \-----------------------------------------------------------------/
 *
 *
 * @Note Akka can do millions of messages per second per actor per core.
 */
object Namespace {

  final case class Subscribe[T <: OnData](observer: Observer[T])(implicit val tag: TypeTag[T])

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def endpoint: String
    def packet: Packet

    import ConnectionActive._
    def replyMessage(msg: String)(implicit resolver: ActorRef) = resolver ! SendMessage(context.sessionId, endpoint, msg)
    def replyJson(json: String)(implicit resolver: ActorRef) = resolver ! SendJson(context.sessionId, endpoint, json)
    def replyEvent(name: String, args: String)(implicit resolver: ActorRef) = resolver ! SendEvent(context.sessionId, endpoint, name, Left(args))
    def replyEvent(name: String, args: Seq[String])(implicit resolver: ActorRef) = resolver ! SendEvent(context.sessionId, endpoint, name, Right(args))
    def reply(packets: Packet*)(implicit resolver: ActorRef) = resolver ! SendPackets(context.sessionId, packets)
    def ack(args: String)(implicit resolver: ActorRef) = resolver ! SendAck(context.sessionId, packet.asInstanceOf[DataPacket], args)
    def broadcast(topic: String, packet: Packet)(implicit resolver: ActorRef) = resolver ! Broadcast(context.sessionId, topic, packet)
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val endpoint: String, implicit val packet: Packet) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val endpoint: String, implicit val packet: Packet) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val endpoint: String, implicit val packet: DataPacket) extends OnData
  final case class OnJson(json: String, context: ConnectionContext)(implicit val endpoint: String, implicit val packet: DataPacket) extends OnData
  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val endpoint: String, implicit val packet: DataPacket) extends OnData

  def subscribe[T <: OnData: TypeTag](observer: Observer[T])(namespace: ActorRef) {
    namespace ! Subscribe(observer)
  }
}

/**
 * Namespace is refered to endpoint for observers and will subscribe to topic 'namespace' of mediator
 */
class Namespace(endpoint: String, mediator: ActorRef) extends Actor with ActorLogging {
  import Namespace._

  val namespace: String = socketio.namespaceFor(endpoint)

  val connectChannel = Subject[OnConnect]()
  val disconnectChannel = Subject[OnDisconnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  private var isMediatorSubscribed: Boolean = _

  def subscribeMediatorForNamespace(action: () => Unit) = {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      mediator.ask(akka.contrib.pattern.DistributedPubSubMediator.Subscribe(namespace, self))(socketio.actorResolveTimeout).mapTo[SubscribeAck] onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to medietor on topic {}: {}", namespace, ex.getMessage)
      }
    } else {
      action()
    }
  }

  def receive: Receive = {
    case x @ Subscribe(observer) =>
      subscribeMediatorForNamespace { () =>
        x.tag.tpe match {
          case t if t =:= typeOf[OnConnect]    => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
          case t if t =:= typeOf[OnDisconnect] => disconnectChannel(observer.asInstanceOf[Observer[OnDisconnect]])
          case t if t =:= typeOf[OnMessage]    => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
          case t if t =:= typeOf[OnJson]       => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
          case t if t =:= typeOf[OnEvent]      => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
          case _                               =>
        }
      }

    case ConnectionActive.OnPacket(packet: ConnectPacket, connContext)    => connectChannel.onNext(OnConnect(packet.args, connContext)(endpoint, packet))
    case ConnectionActive.OnPacket(packet: DisconnectPacket, connContext) => disconnectChannel.onNext(OnDisconnect(connContext)(endpoint, packet))
    case ConnectionActive.OnPacket(packet: MessagePacket, connContext)    => messageChannel.onNext(OnMessage(packet.data, connContext)(endpoint, packet))
    case ConnectionActive.OnPacket(packet: JsonPacket, connContext)       => jsonChannel.onNext(OnJson(packet.json, connContext)(endpoint, packet))
    case ConnectionActive.OnPacket(packet: EventPacket, connContext)      => eventChannel.onNext(OnEvent(packet.name, packet.args, connContext)(endpoint, packet))
  }

}
