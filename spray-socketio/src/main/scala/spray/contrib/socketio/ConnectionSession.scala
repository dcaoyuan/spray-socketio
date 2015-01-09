package spray.contrib.socketio

import akka.actor.{ PoisonPill, Actor, ActorRef, ActorSystem, Props, ActorLogging, Cancellable, ExtensionIdProvider, ExtendedActorSystem, Extension, ExtensionId }
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion
import akka.contrib.pattern.ShardRegion.Passivate
import akka.contrib.pattern.DistributedPubSubMediator.{ Publish, Subscribe, SubscribeAck, Unsubscribe, UnsubscribeAck }
import akka.event.LoggingAdapter
import akka.io.Tcp
import akka.pattern.ask
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.util.ByteString
import org.parboiled.errors.ParsingException
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
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
import spray.contrib.socketio.packet.NoopPacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri

object ConnectionSession extends ExtensionId[ConnectionSessionExtension] with ExtensionIdProvider {
  // -- implementation of akka extention 
  override def get(system: ActorSystem) = super.get(system)
  override def lookup = ConnectionSession
  override def createExtension(system: ExtendedActorSystem) = new ConnectionSessionExtension(system)
  // -- end of implementation of akka extention 

  case object AskConnectedTime

  sealed trait Event extends Serializable

  sealed trait Command extends Serializable {
    def sessionId: String
  }

  final case class CreateSession(sessionId: String) extends Command

  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Command with Event
  final case class Closing(sessionId: String, transportConnection: ActorRef) extends Command with Event
  final case class SubscribeBroadcast(sessionId: String, endpoint: String, room: String) extends Command with Event
  final case class UnsubscribeBroadcast(sessionId: String, endpoint: String, room: String) extends Command with Event

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

  /**
   * ask me to publish an OnBroadcast data
   */
  final case class Broadcast(sessionId: String, room: String, packet: Packet) extends Command

  /**
   * Broadcast event to be published or recevived
   */
  final case class OnBroadcast(sessionId: String, room: String, packet: Packet)

  /**
   * Packet event to be published
   */
  sealed trait OnPacket extends ConsistentHashable with Serializable {
    override def consistentHashKey = endpoint

    def context: ConnectionContext
    def packet: Packet

    final def endpoint = packet.endpoint
    final def sessionId = context.sessionId

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
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val packet: ConnectPacket) extends OnPacket
  final case class OnDisconnect(context: ConnectionContext)(implicit val packet: DisconnectPacket) extends OnPacket
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val packet: MessagePacket) extends OnPacket
  final case class OnJson(json: String, context: ConnectionContext)(implicit val packet: JsonPacket) extends OnPacket
  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val packet: EventPacket) extends OnPacket
  final case class OnNoop(context: ConnectionContext)(implicit val packet: NoopPacket.type) extends OnPacket

  final case class AskStatus(sessionId: String) extends Command
  final case class Status(sessionId: String, connectionTime: Long, location: String) extends Serializable

  val shardName: String = "ConnectionSessions"

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
  def startSharding(system: ActorSystem, entryProps: Option[Props]) {
    val sharding = ClusterSharding(system)
    sharding.start(
      entryProps = entryProps,
      typeName = shardName,
      idExtractor = idExtractor,
      shardResolver = shardResolver)
    if (entryProps.isDefined) ClusterReceptionistExtension(system).registerService(sharding.shardRegion(shardName))
  }

  def shardRegion(system: ActorSystem) = ClusterSharding(system).shardRegion(shardName)
  def shardRegionPath(system: ActorSystem) = {
    val shardingGuardianName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
    s"/user/${shardingGuardianName}/${shardName}"
  }

  /**
   * A broker actor that runs outside of the cluster to forward msg to sharding actor easily.
   *
   * @param path sharding service's path
   * @param originalClient [[ClusterClient]] to access Cluster
   */
  class ClusterClientBroker(servicePath: String, originalClient: ActorRef) extends Actor with ActorLogging {
    def receive: Actor.Receive = {
      case cmd: Command => originalClient forward ClusterClient.Send(servicePath, cmd, false)
    }
  }

  final class State(val context: ConnectionContext, var transportConnection: ActorRef, var topics: immutable.Set[String]) extends Serializable {
    override def equals(other: Any) = {
      other match {
        case x: State => x.context == this.context && x.transportConnection == this.transportConnection && x.topics == this.topics
        case _        => false
      }
    }

    override def toString = {
      new StringBuilder().append("State(")
        .append("context=").append(context)
        .append(", transConn=").append(transportConnection)
        .append(", topics=").append(topics).append(")")
        .toString
    }
  }

  val GlobalConnectPacket = ConnectPacket()
  val GlobalDisconnectPacket = DisconnectPacket()

  case object HeartbeatTick
  case object CloseTimeout
  case object IdleTimeout

  private def heartbeatDelay = ThreadLocalRandom.current.nextInt((math.min(socketio.Settings.HeartbeatTimeout, socketio.Settings.CloseTimeout) * 0.618).round.toInt).seconds
}

/**
 *
 * transportConnection <1..n--1> connectionSession <1--1> connContext <1--n> transport
 */
trait ConnectionSession { _: Actor =>
  import ConnectionSession._
  import context.dispatcher

  def log: LoggingAdapter
  def recoveryFinished: Boolean
  def recoveryRunning: Boolean

  def mediator: ActorRef

  private lazy val scheduler = SocketIOExtension(context.system).scheduler

  private val startTime = System.currentTimeMillis

  private var idleTimeoutTask: Option[Cancellable] = None

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  private var heartbeatTask: Option[Cancellable] = None
  private var closeTimeoutTask: Option[Cancellable] = None

  protected var pendingPackets = immutable.Queue[Packet]()

  private var _state: State = _ // have to init it lazy
  def state = {
    if (_state == null) {
      _state = new State(new ConnectionContext(), context.system.deadLetters, immutable.Set())
    }
    _state
  }
  def state_=(state: State) {
    _state = state
  }

  def updateState(evt: Any, newState: State) {
    state = newState
  }

  def doStop() {
    deactivate()
    disableIdleTimeout()

    if (SocketIOExtension(context.system).Settings.isCluster) {
      context.parent ! Passivate(stopMessage = PoisonPill)
    } else {
      self ! PoisonPill
    }
  }

  def deactivate() {
    log.debug("deactivated.")
    disableHeartbeat()
    disableCloseTimeout()
  }

  def working: Receive = {
    // ---- heartbeat / timeout
    case HeartbeatTick => // scheduled sending heartbeat
      sendPacket(HeartbeatPacket)

      // keep previous close timeout. We may skip one closetimeout for this heartbeat, but we'll reset one at next heartbeat.
      if (closeTimeoutTask.fold(true)(_.isCancelled)) {
        enableCloseTimeout()
      }

    case CloseTimeout =>
      state.transportConnection ! Tcp.Close
      log.info("CloseTimeout disconnect: {}, state: {}", state.context.sessionId, state)
      if (state.context.isConnected) { // make sure only send disconnect packet one time
        onPacket(null)(GlobalDisconnectPacket)
      }

    case IdleTimeout =>
      log.info("IdleTimeout stop: {}, state: {}", state.context.sessionId, state)
      doStop()

    // ---- on data
    case cmd @ OnFrame(sessionId, payload) =>
      onPayload(cmd)(payload)
    case cmd @ OnPost(sessionId, transportConnection, payload) =>
      // response an empty entity to release POST before message processing
      state.context.transport.write(state.context, transportConnection, "")
      onPayload(cmd)(payload)
    case OnGet(sessionId, transportConnection) =>
      pendingPackets = state.context.transport.writeSingle(state.context, transportConnection, isSendingNoopWhenEmpty = true, pendingPackets)

    // ---- sending
    case SendMessage(sessionId, endpoint, msg)      => sendMessage(endpoint, msg)
    case SendJson(sessionId, endpoint, json)        => sendJson(endpoint, json)
    case SendEvent(sessionId, endpoint, name, args) => sendEvent(endpoint, name, args)
    case SendPackets(sessionId, packets)            => sendPacket(packets: _*)
    case SendAck(sessionId, packet, args)           => sendAck(packet, args)

    // ---- broadcast
    case Broadcast(sessionId, room, packet)         => publishToBroadcast(OnBroadcast(sessionId, room, packet))
    case OnBroadcast(senderSessionId, room, packet) => sendPacket(packet) // write to client

    case cmd @ SubscribeBroadcast(sessionId, endpoint, room) =>
      val topic = socketio.topicForBroadcast(endpoint, room)
      state.topics = state.topics + topic
      updateState(cmd, state)
      subscribeBroadcast(topic)

    case cmd @ UnsubscribeBroadcast(sessionId, endpoint, room) =>
      val topic = socketio.topicForBroadcast(endpoint, room)
      state.topics = state.topics - topic
      updateState(cmd, state)
      unsubscribeBroadcast(topic)

    // -- connecting / closing  
    case CreateSession(_) => // may be forwarded by region, just ignore it.

    case cmd @ Connecting(sessionId, query, origins, transportConnection, transport) => // transport fired connecting command
      disableIdleTimeout()
      disableCloseTimeout()
      enableHeartbeat()

      state.context.sessionId match {
        case null =>
          state.context.sessionId = sessionId
          state.context.query = query
          state.context.origins = origins
          state.context.transport = transport
          state.transportConnection = transportConnection

          updateState(cmd, state)
          onPacket(cmd)(GlobalConnectPacket)
        case existed =>
          state.context.transport = transport
          state.transportConnection = transportConnection

          if (recoveryFinished) {
            updateState(cmd, state)
            onPacket(cmd)(GlobalConnectPacket)
          }
      }

      if (recoveryFinished) {
        log.info("Connecting: {}, state: {}", sessionId, state)
      }

    case cmd @ Closing(sessionId, transportConnection) => // transport fired closing command
      if (recoveryFinished) {
        log.info("Closing: {}, state: {}", sessionId, state)
      }
      if (state.transportConnection == transportConnection) {
        if (state.context.isConnected) { // make sure only send disconnect packet one time
          onPacket(cmd)(GlobalDisconnectPacket)
        }
      }

    // TODO we do not monitor state.transportConnection any more, but we can try to monitor the Node where the transportConnection resided.
    //    case MemberUp(member) =>
    //      log.info("Member is Up: {}", member.address)
    //    case UnreachableMember(member) =>
    //      log.info("Member detected as unreachable: {}", member)
    //    case MemberRemoved(member, previousStatus) =>
    //      log.info("Member is Removed: {} after {}", member.address, previousStatus)
    //    case _: MemberEvent => // ignore    case MemberEvent(ref) =>
    //      log.info("Terminated: {}, {}", state, )
    //      if (state.transportConnection == ref) {
    //        if (state.context.isConnected) {
    //          onPacket(null)(GlobalDisconnectPacket)
    //        }
    //      }

    // --- Stats
    case AskConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

    case AskStatus(sessionId) =>
      val sessionId = if (state.context.isConnected) state.context.sessionId else null
      val location = if (state.transportConnection.path != null) state.transportConnection.path.toSerializationFormat else null
      sender() ! Status(sessionId, System.currentTimeMillis - startTime, location)
  }

  // --- reacts

  private def onPayload(cmd: Command)(payload: ByteString) {
    PacketParser(payload) match {
      case Success(packets)              => packets foreach onPacket(cmd)
      case Failure(ex: ParsingException) => log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
      case Failure(ex)                   => log.warning("Exception during parse socket.io packet: {} ..., due to: {}", payload.take(50).utf8String, ex)
    }
  }

  private def onPacket(cmd: Command)(packet: Packet) {
    packet match {
      case HeartbeatPacket =>
        log.debug("got heartbeat")
        disableCloseTimeout()

      case ConnectPacket(endpoint, args) =>
        if (recoveryFinished) {
          publishToTopic(packet, state.context)
        }

        val topic = socketio.topicForBroadcast(endpoint, "")
        state.topics = state.topics + topic
        state.context.isConnected = true
        updateState(cmd, state)
        subscribeBroadcast(topic).onComplete {
          case Success(ack) =>
            // bounce connect packet back to client
            if (recoveryFinished) {
              sendPacket(packet)
            }
          case Failure(ex) =>
            log.warning("Failed to subscribe to {} on topic [{}]: {}", mediator, topic, ex.getMessage)
        }

      case DisconnectPacket(endpoint) =>
        if (endpoint == "") {
          if (recoveryFinished) {
            publishDisconnect(GlobalDisconnectPacket, state.context)
          }
          cmd match {
            case _: Closing => // ignore Closing (which is sent from the Transport) to avoid cycle
            case _          => state.transportConnection ! Tcp.Close
          }
          state.topics foreach unsubscribeBroadcast
          state.topics = Set()
          state.transportConnection = context.system.deadLetters
          state.context.isConnected = false
          updateState(cmd, state)

          deactivate()
          enableIdleTimeout()
        } else {
          if (recoveryFinished) {
            publishToTopic(packet, state.context)
          }
          val topic = socketio.topicForBroadcast(endpoint, "")
          state.topics = state.topics - topic
          updateState(cmd, state)
          unsubscribeBroadcast(topic)
        }

      case _ =>
        // for data packet that requests ack and has no ack data, automatically ack
        packet match {
          case x: DataPacket if x.isAckRequested && !x.hasAckData => sendAck(x, "[]")
          case _ =>
        }
        publishToTopic(packet, state.context)
    }
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
    if (state.context.isConnected) {
      updatePendingPackets = state.context.transport.flushOrWait(state.context, state.transportConnection, updatePendingPackets)
    }
    pendingPackets = updatePendingPackets
  }

  def sendAck(originalPacket: DataPacket, args: String) {
    sendPacket(AckPacket(originalPacket.id, args))
  }

  def publishDisconnect(packet: DisconnectPacket, connContext: ConnectionContext) {
    mediator ! Publish(socketio.topicForDisconnect, toOnPacket(packet, connContext))
  }

  def publishToTopic(packet: Packet, connContext: ConnectionContext) {
    val msg = toOnPacket(packet, connContext)
    mediator ! Publish(socketio.topicForEndpoint(packet.endpoint), msg, sendOneMessageToEachGroup = false)
  }

  def toOnPacket(packet: Packet, connContext: ConnectionContext) = packet match {
    case x: ConnectPacket    => OnConnect(x.args, connContext)(x)
    case x: DisconnectPacket => OnDisconnect(connContext)(x)
    case x: MessagePacket    => OnMessage(x.data, connContext)(x)
    case x: JsonPacket       => OnJson(x.json, connContext)(x)
    case x: EventPacket      => OnEvent(x.name, x.args, connContext)(x)
    case _ =>
      log.warning("Packet should be processed internal: {}", packet)
      OnNoop(connContext)(NoopPacket)
  }

  def publishToBroadcast(msg: OnBroadcast) {
    mediator ! Publish(socketio.topicForBroadcast(msg.packet.endpoint, msg.room), msg, sendOneMessageToEachGroup = false)
  }

  def subscribeBroadcast(topic: String): Future[SubscribeAck] = {
    mediator.ask(Subscribe(topic, None, self))(socketio.actorResolveTimeout).mapTo[SubscribeAck]
  }

  def unsubscribeBroadcast(topic: String) {
    mediator.ask(Unsubscribe(topic, None, self))(socketio.actorResolveTimeout).mapTo[UnsubscribeAck]
  }

  // ---- heartbeat and timeout

  def enableHeartbeat() {
    log.debug("enabled heartbeat, will repeatly send heartbeat every {} seconds", socketio.Settings.heartbeatInterval.seconds)
    heartbeatTask foreach { _.cancel } // it better to confirm previous heartbeatTask was cancled
    heartbeatTask = Some(scheduler.schedule(heartbeatDelay, socketio.Settings.heartbeatInterval.seconds, self, HeartbeatTick))
  }

  def enableCloseTimeout() {
    log.debug("enabled close-timeout, will disconnect in {} seconds", socketio.Settings.CloseTimeout)
    closeTimeoutTask foreach { _.cancel } // it better to confirm previous closeTimeoutTask was cancled
    if (context != null) {
      closeTimeoutTask = Some(scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds, self, CloseTimeout))
    }
  }

  def enableIdleTimeout() {
    log.debug("enabled idle-timeout, will stop/exit in {} seconds", socketio.Settings.IdleTimeout)
    idleTimeoutTask foreach { _.cancel } // it better to confirm previous idleTimeoutTask was cancled
    if (context != null) {
      idleTimeoutTask = Some(scheduler.scheduleOnce(socketio.Settings.IdleTimeout.seconds, self, IdleTimeout))
    }
  }

  def disableHeartbeat() {
    log.debug("disabled heartbeat")
    heartbeatTask foreach { _.cancel }
    heartbeatTask = None
  }

  def disableCloseTimeout() {
    log.debug("disabled close-timeout")
    closeTimeoutTask foreach { _.cancel }
    closeTimeoutTask = None
  }

  def disableIdleTimeout() {
    log.debug("disabled idle-timeout")
    idleTimeoutTask foreach { _.cancel }
    idleTimeoutTask = None
  }
}

class ConnectionSessionExtension(system: ExtendedActorSystem) extends Extension {

  lazy val originalClusterClient = {
    import scala.collection.JavaConversions._
    val initialContacts = system.settings.config.getStringList("spray.socketio.cluster.client-initial-contacts").toSet
    system.actorOf(ClusterClient.props(initialContacts map system.actorSelection), "socketio-session-cluster-client")
  }

  lazy val clusterClient = {
    val path = ConnectionSession.shardRegionPath(system)
    system.actorOf(Props(classOf[ConnectionSession.ClusterClientBroker], path, originalClusterClient))
  }
}
