package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Terminated
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.ShardRegion
import akka.pattern.ask
import akka.routing.ActorRefRoutee
import akka.routing.Router
import akka.routing.RoutingLogic
import akka.util.Timeout
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionSession
import spray.contrib.socketio.ConnectionSession.OnPacket
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.SocketIOExtension
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
 *    +======node======+  |     +===========namesapce-node==============+     |
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
 * Namespace is sharding actor in socketio cluster, but, socketio's tranport/session nodes
 * are not aware of Namespace actors, because all messages in that cluster are sent to mediator.
 *
 * Namespace actors just accept messages via mediator, and then deliver them to
 * subscribted channels.
 */
object Namespace {

  def props(mediator: ActorRef, groupRoutingLogic: RoutingLogic) = Props(classOf[Namespace], mediator, groupRoutingLogic)

  // --- Observable data
  sealed trait OnData extends Serializable {
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

  val shardName: String = "Namespaces"

  val idExtractor: ShardRegion.IdExtractor = {
    case x: DistributedPubSubMediator.Subscribe      => (socketio.topicForNamespace(x.topic), x)
    case x: DistributedPubSubMediator.Unsubscribe    => (socketio.topicForNamespace(x.topic), x)
    case x: DistributedPubSubMediator.SubscribeAck   => (socketio.topicForNamespace(x.subscribe.topic), x)
    case x: DistributedPubSubMediator.UnsubscribeAck => (socketio.topicForNamespace(x.unsubscribe.topic), x)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case x: DistributedPubSubMediator.Subscribe      => hashForShard(socketio.topicForNamespace(x.topic))
    case x: DistributedPubSubMediator.Unsubscribe    => hashForShard(socketio.topicForNamespace(x.topic))
    case x: DistributedPubSubMediator.SubscribeAck   => hashForShard(socketio.topicForNamespace(x.subscribe.topic))
    case x: DistributedPubSubMediator.UnsubscribeAck => hashForShard(socketio.topicForNamespace(x.unsubscribe.topic))
  }

  private def hashForShard(topic: String) = (math.abs(topic.hashCode) % 100).toString

  /**
   * It is recommended to load the ClusterReceptionistExtension when the actor
   * system is started by defining it in the akka.extensions configuration property:
   *   akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]
   */
  def startSharding(system: ActorSystem, entryProps: Option[Props]) {
    val sharding = ClusterSharding(system)
    sharding.start(
      entryProps = entryProps,
      typeName = shardName,
      idExtractor = idExtractor,
      shardResolver = shardResolver)
    if (entryProps.isDefined) ClusterReceptionistExtension(system).registerService(sharding.shardRegion(shardName))
  }

  final class SystemSingletons(system: ActorSystem) {
    lazy val clusterClient = {
      startSharding(system, None)
      val shardingGuardianName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
      val path = s"/user/${shardingGuardianName}/${shardName}"
      val originalClusterClient = SocketIOExtension(system).clusterClient
      system.actorOf(Props(classOf[ProxiedClusterClient], path, originalClusterClient))
    }
  }

  private var singletons: SystemSingletons = _
  private val singletonsMutex = new AnyRef
  /**
   * Get the SystemSingletons, create it if none existed.
   *
   * @Note only one will be created no matter how many ActorSystems, actually
   * one ActorSystem per application usaully.
   */
  def apply(system: ActorSystem): SystemSingletons = {
    if (singletons eq null) {
      singletonsMutex synchronized {
        if (singletons eq null) {
          singletons = new SystemSingletons(system)
        }
      }
    }
    singletons
  }

  /**
   * A proxy actor that runs on the business nodes to make forwarding msg to Namespace easily.
   *
   * @param path Namespace sharding service's path
   * @param originalClient [[ClusterClient]] to access SocketIO Cluster
   */
  class ProxiedClusterClient(shardingServicePath: String, originalClient: ActorRef) extends Actor with ActorLogging {
    def receive = {
      case x: DistributedPubSubMediator.Subscribe      => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
      case x: DistributedPubSubMediator.Unsubscribe    => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
      case x: DistributedPubSubMediator.SubscribeAck   => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
      case x: DistributedPubSubMediator.UnsubscribeAck => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
    }
  }
}

/**
 * Namespace is refered to endpoint for observers and will subscribe to topic 'namespace' of mediator
 */
class Namespace(mediator: ActorRef, groupRoutingLogic: RoutingLogic) extends Actor with ActorLogging {
  import Namespace._

  val groupRouter = Router(groupRoutingLogic)

  var channels = Set[ActorRef]() // ActorRef of Channel
  var groupToChannels: Map[Option[String], Set[ActorRefRoutee]] = Map.empty.withDefaultValue(Set.empty)

  private var isMediatorSubscribed: Boolean = _

  private def subscribeToMediator(topic: String)(action: () => Unit) = {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      implicit val timeout = Timeout(socketio.namespaceSubscribeTimeout)
      val f1 = mediator.ask(DistributedPubSubMediator.Subscribe(socketio.topicForDisconnect, self)).mapTo[DistributedPubSubMediator.SubscribeAck]
      val f2 = mediator.ask(DistributedPubSubMediator.Subscribe(socketio.topicForNamespace(topic), self)).mapTo[DistributedPubSubMediator.SubscribeAck]
      Future.sequence(List(f1, f2)).onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to mediator on topic {}: {}", socketio.topicForNamespace(topic), ex.getMessage)
      }
    } else {
      action()
    }
  }

  private def unsubscribeToMediator(topic: String)(action: () => Unit) = {
    if (isMediatorSubscribed && channels.isEmpty) {
      import context.dispatcher
      implicit val timeout = Timeout(socketio.namespaceSubscribeTimeout)
      val f1 = mediator.ask(DistributedPubSubMediator.Unsubscribe(socketio.topicForDisconnect, self)).mapTo[DistributedPubSubMediator.UnsubscribeAck]
      val f2 = mediator.ask(DistributedPubSubMediator.Unsubscribe(socketio.topicForNamespace(topic), self)).mapTo[DistributedPubSubMediator.UnsubscribeAck]
      Future.sequence(List(f1, f2)).onComplete {
        case Success(ack) =>
          isMediatorSubscribed = false
          action()
        case Failure(ex) =>
          log.warning("Failed to unsubscribe to mediator on topic {}: {}", socketio.topicForNamespace(topic), ex.getMessage)
      }
    } else {
      action()
    }
  }

  def receive: Receive = {
    case x @ DistributedPubSubMediator.Subscribe(topic, group, channel) =>
      val topic1 = topic match {
        case socketio.GlobalTopic => ""
        case x                    => x
      }

      val commander = sender()
      subscribeToMediator(topic1) { () =>
        insertSubscription(group, channel)
        commander ! DistributedPubSubMediator.SubscribeAck(x)
        log.info("{} successfully subscribed to topic [{}] under group [{}]", channel, topic, group)
      }

    case x @ DistributedPubSubMediator.Unsubscribe(topic, group, channel) =>
      val topic1 = topic match {
        case socketio.GlobalTopic => ""
        case x                    => x
      }

      val commander = sender()
      removeSubscription(group, channel)
      commander ! DistributedPubSubMediator.UnsubscribeAck(x)
      log.info("{} successfully unsubscribed to topic [{}] under group [{}]", channel, topic, group)
      if (channels.isEmpty) {
        unsubscribeToMediator(topic1) { () => }
      }

    case Terminated(ref) => removeSubscription(ref)

    case x: OnPacket[_] => // messages got via mediator 
      groupToChannels foreach {
        case (None, channels) => channels foreach (_.ref ! x)
        case (_, channels)    => groupRouter.withRoutees(channels.toVector).route(x, self)
      }
  }

  def existChannel(channel: ActorRef) = {
    groupToChannels exists { case (group, channels) => channels.contains(ActorRefRoutee(channel)) }
  }

  def insertSubscription(group: Option[String], channel: ActorRef) {
    if (!channels.contains(channel)) {
      context watch channel
      channels += channel
    }
    groupToChannels = groupToChannels.updated(group, groupToChannels(group) + ActorRefRoutee(channel))
  }

  def removeSubscription(group: Option[String], channel: ActorRef) {
    if (!existChannel(channel)) {
      context unwatch channel
      channels -= channel
    }
    groupToChannels = groupToChannels.updated(group, groupToChannels(group) - ActorRefRoutee(channel))
  }

  def removeSubscription(channel: ActorRef) {
    context unwatch channel
    channels -= channel
    groupToChannels = for {
      (group, channels) <- groupToChannels
    } yield (group -> (channels - ActorRefRoutee(channel)))
  }

}
