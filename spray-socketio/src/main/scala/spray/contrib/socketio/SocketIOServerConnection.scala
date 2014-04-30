package spray.contrib.socketio

import akka.actor.{ PoisonPill, Cancellable, Actor, ActorLogging, ActorRef }
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.FrameCommand
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.http.HttpRequest

/**
 *
 *             +--------------------------------------+
 *             |     +---ConnectionActive(actor)      |
 *             |     |                                | -----------  virtual connection (identified by sessionId etc.)
 *             |     +---ConnectionContext            |
 *             +--------------------------------------+
 *                                |1
 *                                |
 *                                |1..n
 *                   +--------------------------+
 *                   |   transportConnection    |
 *                   +--------------------------+
 *                                |1
 *                                |
 *                    websocket   |   xhr-polling
 *                      +---------+---------+
 *                      |                   |
 *                      |                   |
 *                      |1                  |1..n
 *               Ws Connection       Http Connections
 *
 * ================================================================
 * Check opened tcp connections:
 *
 * For open (established) tcp connections:
 * # netstat -tn
 *
 * To additionally get the associated PID for each connection:
 * # netstat -tnp
 *
 * Check histogram of java object heap; if the "live" suboption is
 * specified, only count live objects (will also force GC):
 * # jmap -histo[:live] <pid>
 *
 * Force GC (JDK 7+):
 * jcmd <pid> GC.run
 * ================================================================
 */
trait SocketIOServerConnection extends ActorLogging { _: Actor =>
  import context.dispatcher

  def serverConnection: ActorRef
  def resolver: ActorRef
  def sessionIdGenerator: HttpRequest => Future[String] = { req => Future(UUID.randomUUID.toString) } // default one

  var closeTimeout: Option[Cancellable] = None

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatHandler: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  val heartbeatInterval = socketio.Settings.HeartbeatTimeout * 0.618

  val heartbeatFrameCommand = FrameCommand(TextFrame(HeartbeatPacket.render))

  implicit val soConnContext = new socketio.SoConnectingContext(null, sessionIdGenerator, serverConnection, self, resolver, log, context.dispatcher)

  def receive = handleSocketioHandshake orElse handleWebsocketConnecting orElse handleXrhpollingConnecting orElse handleHeartbeat orElse genericLogic orElse handleTerminate

  override def postStop(): Unit = {
    disableHeartbeat
    disableHeartbeatTimeout
    disableCloseTimeout
    if (soConnContext.sessionId != null) {
      resolver ! ConnectionActive.Closing(soConnContext.sessionId)
    }
  }

  def handleTerminate: Receive = {
    case x: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("{}: http connection stopped due to {}.", serverConnection.path, x)
  }

  def handleHeartbeat: Receive = {
    case HeartbeatPacket => // schedule to send heartbeat
      serverConnection ! heartbeatFrameCommand

    case socketio.GotHeartbeat =>
      disableCloseTimeout
      resetHeartbeatTimeout
  }

  def handleSocketioHandshake: Receive = {
    case socketio.HandshakeRequest(ok) =>
      log.debug("{}: socketio handshaked.", serverConnection.path)
  }

  def handleWebsocketConnecting: Receive = {
    case req @ websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
        case wsContext: websocket.HandshakeContext =>
          sender() ! UHttp.UpgradeServer(websocket.pipelineStage(self, wsContext), wsContext.response)
          socketio.wsConnecting(wsContext.request) foreach { _ =>
            log.debug("{}: socketio on websocket connected.", serverConnection.path)
          }
      }

    case UHttp.Upgraded =>
      log.debug("{}: upgraded.", serverConnection.path)
      disableCloseTimeout
      enableHeartbeat
      resetHeartbeatTimeout
      context.become(handleHeartbeat orElse handleWebsocket orElse handleTerminate)
  }

  def handleWebsocket: Receive = {
    case frame @ socketio.WsFrame(ok) =>
      log.debug("Got {}", frame)
  }

  def handleXrhpollingConnecting: Receive = {
    case req @ socketio.HttpGet(ok) =>
      disableCloseTimeout
      log.debug("{}: socketio GET {}", serverConnection.path, req.entity)

    case req @ socketio.HttpPost(ok) =>
      disableCloseTimeout
      log.debug("{}: socketio POST {}", serverConnection.path, req.entity)
  }

  def genericLogic: Receive

  // --- timeout handling:

  def disableHeartbeat() {
    heartbeatHandler foreach (_.cancel)
    heartbeatHandler = None
  }

  def enableHeartbeat() {
    if (heartbeatHandler.isEmpty || heartbeatHandler.nonEmpty && heartbeatHandler.get.isCancelled) {
      heartbeatHandler = Some(context.system.scheduler.schedule(heartbeatInterval.seconds, heartbeatInterval.seconds, self, HeartbeatPacket))
    }
  }

  // --- heartbeat timeout

  def resetHeartbeatTimeout() {
    heartbeatTimeout foreach (_.cancel)
    heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
      enableCloseTimeout()
    })
  }

  def disableHeartbeatTimeout() {
    heartbeatTimeout foreach (_.cancel)
    heartbeatTimeout = None
  }

  // --- close timeout

  def enableCloseTimeout() {
    log.debug("{}: close timeout, will close in {} seconds", self.path, socketio.Settings.CloseTimeout)
    closeTimeout foreach (_.cancel)
    if (context != null) {
      closeTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds) {
        log.warning("{}: stoped due to close timeout", self.path)
        disableHeartbeat()
        self ! PoisonPill
      })
    }
  }

  def disableCloseTimeout() {
    closeTimeout foreach (_.cancel)
    closeTimeout = None
  }

}
