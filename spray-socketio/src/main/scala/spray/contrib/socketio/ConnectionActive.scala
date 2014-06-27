package spray.contrib.socketio

import akka.actor.{ PoisonPill, Actor, ActorRef, Terminated, ActorSystem, Props, ActorLogging, ActorSelection }
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion
import akka.contrib.pattern.DistributedPubSubMediator.{ Publish, Subscribe, SubscribeAck, Unsubscribe }
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.util.ByteString
import org.parboiled.errors.ParsingException
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio
import spray.contrib.socketio.packet.AckPacket
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DataPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri

object ConnectionActive {

  case object AskConnectedTime

  sealed trait Event extends Serializable

  final case class ConnectingEvent(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Event
  final case class SubscribeBroadcastEvent(sessionId: String, endpoint: String, room: String) extends Event
  final case class UnsubscribeBroadcastEvent(sessionId: String, endpoint: String, room: String) extends Event

  sealed trait Command extends Serializable {
    def sessionId: String
  }

  final case class CreateSession(sessionId: String) extends Command
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Command
  final case class Closing(sessionId: String, transportConnection: ActorRef) extends Command

  // called by connection
  final case class OnGet(sessionId: String, transportConnection: ActorRef) extends Command
  final case class OnPost(sessionId: String, transportConnection: ActorRef, payload: ByteString) extends Command
  final case class OnFrame(sessionId: String, payload: ByteString) extends Command

  // called by business logic
  final case class SendMessage(sessionId: String, endpoint: String, msg: String) extends Command
  final case class SendJson(sessionId: String, endpoint: String, json: String) extends Command
  final case class SendEvent(sessionId: String, endpoint: String, name: String, args: Either[String, Seq[String]]) extends Command
  final case class SendPackets(sessionId: String, packets: Seq[Packet]) extends Command

  final case class SendAck(sessionId: String, originalPacket: DataPacket, args: String) extends Command

  final case class SubscribeBroadcast(sessionId: String, endpoint: String, room: String) extends Command
  final case class UnsubscribeBroadcast(sessionId: String, endpoint: String, room: String) extends Command

  /**
   * ask me to publish an OnBroadcast data
   */
  final case class Broadcast(sessionId: String, room: String, packet: Packet) extends Command

  final case class GetStatus(sessionId: String) extends Command

  final case class Status(sessionId: String, connectionTime: Long, location: String) extends Serializable

  /**
   * Broadcast event to be published or recevived
   */
  final case class OnBroadcast(sessionId: String, room: String, packet: Packet)

  /**
   * Packet event to be published
   */
  final case class OnPacket[T <: Packet](packet: T, connContext: ConnectionContext) extends ConsistentHashable {
    override def consistentHashKey: Any = connContext.sessionId
  }

  val shardName: String = "ConnectionActives"

  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.sessionId, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case cmd: Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }

  /**
   * It is recommended to load the ClusterReceptionistExtension when the actor
   * system is started by defining it in the akka.extensions configuration property:
   *   akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]
   */
  def startShard(system: ActorSystem, connectionActiveProps: Props) {
    ClusterSharding(system).start(
      typeName = ConnectionActive.shardName,
      entryProps = Some(connectionActiveProps),
      idExtractor = ConnectionActive.idExtractor,
      shardResolver = ConnectionActive.shardResolver)
    ClusterReceptionistExtension(system).registerService(
      ClusterSharding(system).shardRegion(ConnectionActive.shardName))
  }

  final class SystemSingletons(system: ActorSystem) {
    lazy val clusterClient: ActorRef = {
      import scala.collection.JavaConversions._
      val initialContacts = system.settings.config.getStringList("spray.socketio.cluster.client-initial-contacts").toSet
      system.actorOf(ClusterClient.props(initialContacts map system.actorSelection), "socketio-cluster-connactive-client")
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
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
trait ConnectionActive { _: Actor =>
  import ConnectionActive._
  import context.dispatcher

  def log: LoggingAdapter

  def namespaceMediator: ActorRef

  def broadcastMediator: ActorRef

  var connectionContext: Option[ConnectionContext] = None
  var transportConnection: ActorRef = _

  var pendingPackets = immutable.Queue[Packet]()
  var topics = immutable.Set[String]()

  val startTime = System.currentTimeMillis

  val connectPacket = ConnectPacket()
  val disconnectPacket = DisconnectPacket()

  var disconnected = false

  def connected() {
    onPacket(connectPacket)
  }

  def update(event: Event) = {
    event match {
      case ConnectingEvent(sessionId, query, origins, ref, transport) =>
        connectionContext = Some(new ConnectionContext(sessionId, query, origins))
        transportConnection = ref
        connectionContext.foreach(_.bindTransport(transport))
      case SubscribeBroadcastEvent(_, endpoint, room) =>
        val topic = socketio.topicForBroadcast(endpoint, room)
        topics += topic
        subscribeBroadcast(topic)
      case UnsubscribeBroadcastEvent(_, endpoint, room) =>
        val topic = socketio.topicForBroadcast(endpoint, room)
        topics -= topic
        unsubscribeBroadcast(topic)
    }
  }

  def processConnectingEvent(conn: ConnectingEvent) {
    update(conn)
    connected()
  }

  def processSubscribeBroadcastEvent(evt: SubscribeBroadcastEvent) {
    update(evt)
  }

  def processUnsubscribeBroadcastEvent(evt: UnsubscribeBroadcastEvent) {
    update(evt)
  }

  def close() {
    self ! PoisonPill
  }

  def working: Receive = {
    case CreateSession(_) => // may be forwarded by resolver, just ignore it.

    case conn @ Connecting(sessionId, query, origins, transportConn, transport) =>
      log.info("Connecting: {}, {}", sessionId, connectionContext)

      connectionContext match {
        case Some(existed) =>
          transportConnection = transportConn
          existed.bindTransport(transport)
          connected()
        case None =>
          processConnectingEvent(ConnectingEvent(conn.sessionId, conn.query, conn.origins, conn.transportConnection, conn.transport))
      }

    case Closing(sessionId, transportConn) =>
      log.info("Closing: {}, {}", sessionId, connectionContext)
      if (transportConnection == transportConn) {
        if (!disconnected) { //make sure only send disconnect packet one time
          onPacket(disconnectPacket)
        }
        close
      }

    case Terminated(ref) =>
      log.info("Terminated: {}, {}", connectionContext, ref)
      if (transportConnection == ref) {
        if (!disconnected) {
          onPacket(disconnectPacket)
        }
        close
      }

    case OnFrame(sessionId, payload)                     => onFrame(payload)
    case OnGet(sessionId, transportConnection)           => onGet(transportConnection)
    case OnPost(sessionId, transportConnection, payload) => onPost(transportConnection, payload)

    case SendMessage(sessionId, endpoint, msg)           => sendMessage(endpoint, msg)
    case SendJson(sessionId, endpoint, json)             => sendJson(endpoint, json)
    case SendEvent(sessionId, endpoint, name, args)      => sendEvent(endpoint, name, args)
    case SendPackets(sessionId, packets)                 => sendPacket(packets: _*)

    case SendAck(sessionId, packet, args)                => sendAck(packet, args)

    case Broadcast(sessionId, room, packet)              => publishToBroadcast(OnBroadcast(sessionId, room, packet))
    case OnBroadcast(senderSessionId, room, packet)      => sendPacket(packet) // write to client

    case SubscribeBroadcast(sessionId, endpoint, room) =>
      processSubscribeBroadcastEvent(SubscribeBroadcastEvent(sessionId, endpoint, room))

    case UnsubscribeBroadcast(sessionId, endpoint, room) =>
      processUnsubscribeBroadcastEvent(UnsubscribeBroadcastEvent(sessionId, endpoint, room))

    case AskConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

    case GetStatus(sessionId) =>
      val sessionId = if (connectionContext.nonEmpty) connectionContext.get.sessionId else null
      val location = if (transportConnection != null && transportConnection.path != null) transportConnection.path.toSerializationFormat else null
      sender() ! Status(sessionId, System.currentTimeMillis - startTime, location)
  }

  // --- reacts

  private def onPayload(payload: ByteString) {
    PacketParser(payload) match {
      case Success(packets)              => packets foreach onPacket
      case Failure(ex: ParsingException) => log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
      case Failure(ex)                   => log.warning("Exception during parse socket.io packet: {} ..., due to: {}", payload.take(50).utf8String, ex)
    }
  }

  private def onPacket(packet: Packet) {
    packet match {
      case HeartbeatPacket =>

      case ConnectPacket(endpoint, args) =>
        connectionContext foreach { ctx => publishToNamespace(OnPacket(packet, ctx)) }
        if (connectionContext.exists(_.transport == transport.WebSocket)) {
          context watch transportConnection
        }
        disconnected = false
        val topic = socketio.topicForBroadcast(endpoint, "")
        topics += topic
        subscribeBroadcast(topic).onComplete {
          case Success(ack) =>
            // bounce connect packet back to client
            sendPacket(packet)
          case Failure(ex) =>
            log.warning("Failed to subscribe to medietor on topic {}: {}", topic, ex.getMessage)
        }

      case DisconnectPacket(endpoint) =>
        val topic = socketio.topicForBroadcast(endpoint, "")
        topics -= topic
        if (endpoint == "") {
          connectionContext foreach { ctx => publishDisconnect(ctx) }
          if (transportConnection != null) {
            context unwatch transportConnection
          }
          disconnected = true
          topics foreach unsubscribeBroadcast
          topics = Set()
          // do not stop self, waiting for Closing message
        } else {
          connectionContext foreach { ctx => publishToNamespace(OnPacket(packet, ctx)) }
          unsubscribeBroadcast(topic)
        }

      case _ =>
        // for data packet that requests ack and has no ack data, automatically ack
        packet match {
          case x: DataPacket if x.isAckRequested && !x.hasAckData => sendAck(x, "[]")
          case _ =>
        }
        connectionContext foreach { ctx => publishToNamespace(OnPacket(packet, ctx)) }
    }
  }

  def onFrame(payload: ByteString) {
    onPayload(payload)
  }

  def onGet(transportConnection: ActorRef) {
    connectionContext foreach { ctx =>
      pendingPackets = ctx.transport.writeSingle(ctx, transportConnection, isSendingNoopWhenEmpty = true, pendingPackets)
    }
  }

  def onPost(transportConnection: ActorRef, payload: ByteString) {
    connectionContext foreach { ctx =>
      // response an empty entity to release POST before message processing
      ctx.transport.write(ctx, transportConnection, "")
    }
    onPayload(payload)
  }

  def sendMessage(endpoint: String, msg: String) {
    val packet = MessagePacket(-1L, false, endpoint, msg)
    sendPacket(packet)
  }

  def sendJson(endpoint: String, json: String) {
    val packet = JsonPacket(-1L, false, endpoint, json)
    sendPacket(packet)
  }

  def sendEvent(endpoint: String, name: String, args: Either[String, Seq[String]]) {
    val packet = args match {
      case Left(x)   => EventPacket(-1L, false, endpoint, name, x)
      case Right(xs) => EventPacket(-1L, false, endpoint, name, xs)
    }
    sendPacket(packet)
  }

  /**
   * enqueue packets, and let tranport decide whether to flush them right now or pend flush
   */
  def sendPacket(packets: Packet*) {
    var updatePendingPackets = pendingPackets
    packets foreach { packet => updatePendingPackets = updatePendingPackets.enqueue(packet) }
    log.debug("Enqueued {}, pendingPackets: {}", packets, pendingPackets)
    connectionContext foreach { ctx =>
      updatePendingPackets = ctx.transport.flushOrWait(ctx, transportConnection, updatePendingPackets)
    }
    pendingPackets = updatePendingPackets
  }

  def sendAck(originalPacket: DataPacket, args: String) {
    sendPacket(AckPacket(originalPacket.id, args))
  }

  def publishDisconnect(ctx: ConnectionContext) {
    namespaceMediator ! Publish(socketio.topicForDisconnect, OnPacket(disconnectPacket, ctx))
  }

  def publishToNamespace[T <: Packet](msg: OnPacket[T]) {
    namespaceMediator ! Publish(socketio.topicForNamespace(msg.packet.endpoint), msg)
  }

  def publishToBroadcast(msg: OnBroadcast) {
    broadcastMediator ! Publish(socketio.topicForBroadcast(msg.packet.endpoint, msg.room), msg)
  }

  def subscribeBroadcast(topic: String): Future[SubscribeAck] = {
    broadcastMediator.ask(Subscribe(topic, self))(socketio.actorResolveTimeout).mapTo[SubscribeAck]
  }

  def unsubscribeBroadcast(topic: String) {
    broadcastMediator ! Unsubscribe(topic, self)
  }

}

object ConnectionActiveClusterClient {
  def props(path: String, clusterClient: ActorRef) = Props(classOf[ConnectionActiveClusterClient], path, clusterClient)

  def getClient(system: ActorSystem, initialContacts: Set[ActorSelection]) = {
  }

  private var _client: ActorRef = _
  /**
   * Proxied cluster client
   */
  def apply(system: ActorSystem) = {
    if (_client eq null) {
      val originalClient = ConnectionActive(system).clusterClient
      val shardingName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
      _client = system.actorOf(props(s"/user/${shardingName}/${ConnectionActive.shardName}", originalClient))
    }
    _client
  }
}

/**
 * A proxy actor that runs on the namespace nodes to make forwarding msg to ConnectionActive easy.
 *
 * @param path ConnectionActive sharding service's path
 * @param client [[ClusterClient]] to access SocketIO Cluster
 */
class ConnectionActiveClusterClient(path: String, clusterClient: ActorRef) extends Actor with ActorLogging {
  def receive: Actor.Receive = {
    case cmd: ConnectionActive.Command => clusterClient forward ClusterClient.Send(path, cmd, false)
  }
}
