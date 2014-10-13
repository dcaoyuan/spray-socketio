package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.ShardRegion
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionSession
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DataPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet

/**
 *
 *
 *    +===serverConn===+               +===connSession===+              +====namespace===+
 *    |                |    OnFrame    |                 |   OnPacket   |                |
 *    |                | ------------> |                 | -----------> |                |
 *    |                | <------------ |                 | <----------- |                |
 *    |                |  FrameCommand |                 |  SendPackets |                |
 *    +================+               +=================+              +================+
 *
 *
 *    +======node======+
 *    |       mediator----\
 *    |     /     |    |  |
 *    |    /      |    |  |
 *    | conn    conn   |  |
 *    | conn    conn   |  |---------------------------------------------------|
 *    |                |  |                  virtaul MEDIATOR                 |
 *    +================+  |---------------------------------------------------|
 *                        |                                                   |
 *                        |                                                   |
 *    +======node======+  |     +===============busi-node===============+     |
 *    |       mediator----/     | +endpointA (namespace) ----- mediator-------/
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
 *    |   | region      |       |   |     \---> channelA --> [observer]-----\
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
 *
 * Namespace is usually out of socketio cluster, or, socketio's tranport/session cluster
 * are not aware of Namespace actors, all messages in that cluster are sent to mediator.
 *
 * Namespace actors just accept messages via namespaceMediator, and then deliver them to
 * subscribted channels.
 */
object Namespace {

  def props(endpoint: String, mediator: ActorRef) = Props(classOf[Namespace], endpoint, mediator)

  final case class Subscribe(channel: ActorRef)
  final case class SubscribeAck(subcribe: Subscribe)
  final case class Unsubscribe(channel: Option[ActorRef])
  final case class UnsubscribeAck(subcribe: Unsubscribe)

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def packet: Packet

    final def endpoint = packet.endpoint
    final def sessionId = context.sessionId

    import ConnectionSession._

    def replyMessage(msg: String)(implicit region: ActorRef) =
      region ! SendMessage(sessionId, endpoint, msg)

    def replyJson(json: String)(implicit region: ActorRef) =
      region ! SendJson(sessionId, endpoint, json)

    def replyEvent(name: String, args: String)(implicit region: ActorRef) =
      region ! SendEvent(sessionId, endpoint, name, Left(args))

    def replyEvent(name: String, args: Seq[String])(implicit region: ActorRef) =
      region ! SendEvent(sessionId, endpoint, name, Right(args))

    def reply(packets: Packet*)(implicit region: ActorRef) =
      region ! SendPackets(sessionId, packets)

    def ack(args: String)(implicit region: ActorRef) =
      region ! SendAck(sessionId, packet.asInstanceOf[DataPacket], args)

    /**
     * @param room    room to broadcast
     * @param packet  packet to broadcast
     */
    def broadcast(room: String, packet: Packet)(implicit region: ActorRef) =
      region ! Broadcast(sessionId, room, packet)
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val packet: ConnectPacket) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val packet: DisconnectPacket) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val packet: MessagePacket) extends OnData
  final case class OnJson(json: String, context: ConnectionContext)(implicit val packet: JsonPacket) extends OnData
  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val packet: EventPacket) extends OnData

  sealed trait Command extends Serializable {
    def endpoint: String
  }

  val shardName: String = "SocketIONamespaces"

  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.endpoint, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case cmd: Command => (math.abs(cmd.endpoint.hashCode) % 100).toString
  }

  /**
   * It is recommended to load the ClusterReceptionistExtension when the actor
   * system is started by defining it in the akka.extensions configuration property:
   *   akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]
   */
  def startShard(system: ActorSystem, entryProps: Props) {
    val sharding = ClusterSharding(system)
    sharding.start(
      entryProps = Some(entryProps),
      typeName = shardName,
      idExtractor = idExtractor,
      shardResolver = shardResolver)
    ClusterReceptionistExtension(system).registerService(sharding.shardRegion(shardName))
  }

}

/**
 * Namespace is refered to endpoint for observers and will subscribe to topic 'namespace' of mediator
 */
class Namespace(endpoint: String, mediator: ActorRef) extends Actor with ActorLogging {
  import Namespace._

  var channels = Set[ActorRef]() // ActorRef of Channel

  private var isMediatorSubscribed: Boolean = _
  def subscribeToMediator(action: () => Unit) = {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      implicit val timeout = Timeout(socketio.namespaceSubscribeTimeout)
      val f1 = mediator.ask(DistributedPubSubMediator.Subscribe(socketio.topicForDisconnect, self)).mapTo[DistributedPubSubMediator.SubscribeAck]
      val f2 = mediator.ask(DistributedPubSubMediator.Subscribe(socketio.topicForNamespace(endpoint), self)).mapTo[DistributedPubSubMediator.SubscribeAck]
      Future.sequence(List(f1, f2)).onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to mediator on topic {}: {}", socketio.topicForNamespace(endpoint), ex.getMessage)
      }
    } else {
      action()
    }
  }

  def unsubscribeToMediator(action: () => Unit) = {
    if (isMediatorSubscribed && channels.isEmpty) {
      import context.dispatcher
      implicit val timeout = Timeout(socketio.namespaceSubscribeTimeout)
      val f1 = mediator.ask(DistributedPubSubMediator.Unsubscribe(socketio.topicForDisconnect, self)).mapTo[DistributedPubSubMediator.UnsubscribeAck]
      val f2 = mediator.ask(DistributedPubSubMediator.Unsubscribe(socketio.topicForNamespace(endpoint), self)).mapTo[DistributedPubSubMediator.UnsubscribeAck]
      Future.sequence(List(f1, f2)).onComplete {
        case Success(ack) =>
          isMediatorSubscribed = false
          action()
        case Failure(ex) =>
          log.warning("Failed to unsubscribe to mediator on topic {}: {}", socketio.topicForNamespace(endpoint), ex.getMessage)
      }
    } else {
      action()
    }
  }

  import ConnectionSession.OnPacket
  def receive: Receive = {
    case x @ Subscribe(channel) =>
      val commander = sender()
      subscribeToMediator { () =>
        channels += channel
        commander ! SubscribeAck(x)
      }
    case x @ Unsubscribe(channel) =>
      val commander = sender()
      channel match {
        case Some(c) => channels -= c
        case None    => channels = channels.empty
      }
      unsubscribeToMediator { () =>
        commander ! UnsubscribeAck(x)
      }

    // --- messages got via mediator
    case x: OnPacket[_] => channels foreach (_ ! x)
  }

}
