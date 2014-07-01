package spray.contrib.socketio

import akka.actor.{ Cancellable, Actor, ActorLogging, ActorRef }
import akka.io.Tcp
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
import spray.contrib.socketio.transport.{ WebSocket, XhrPolling, Transport }

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

  var connectionActiveClosed = false
  var closeTimeout: Option[Cancellable] = None

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatHandler: Option[Cancellable] = None

  def heartbeatDelay = util.Random.nextInt((math.min(socketio.Settings.HeartbeatTimeout, socketio.Settings.CloseTimeout) * 0.618).round.toInt)

  val HeartbeatFrameCommand = FrameCommand(TextFrame(HeartbeatPacket.render))

  implicit lazy val soConnContext = new socketio.SoConnectingContext(null, sessionIdGenerator, serverConnection, self, resolver, log, context.dispatcher)

  var socketioTransport: Option[Transport] = None

  var socketioHandshaked = false

  val readyBehavior = handleHeartbeat orElse handleSocketioHandshake orElse handleWebsocketConnecting orElse handleXrhpolling orElse genericLogic orElse handleTerminate

  val upgradedBehavior = handleHeartbeat orElse handleWebsocket orElse handleTerminate

  def preReceive(msg: Any) {}

  def postReceive(msg: Any) {}

  def ready: Receive = {
    case msg =>
      preReceive(msg)
      readyBehavior.applyOrElse(msg, unhandled)
      postReceive(msg)
  }

  def upgraded: Receive = {
    case msg =>
      preReceive(msg)
      upgradedBehavior.applyOrElse(msg, unhandled)
      postReceive(msg)
  }

  def receive: Receive = ready

  def handleHeartbeat: Receive = {
    case socketio.SendHeartbeat => // scheduled sending heartbeat
      serverConnection ! HeartbeatFrameCommand
      log.debug("sent heartbeat")

    case socketio.GotHeartbeat =>
      log.debug("got heartbeat")
      resetCloseTimeout() // will be cleared when got next heartbeat
  }

  def handleSocketioHandshake: Receive = {
    case socketio.HandshakeRequest(ok) =>
      socketioHandshaked = true
      resetCloseTimeout()
      log.debug("socketio connection of {} handshaked.", serverConnection.path)
  }

  def handleWebsocketConnecting: Receive = {
    case req @ websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
        case wsContext: websocket.HandshakeContext =>
          clearCloseTimeout()
          sender() ! UHttp.UpgradeServer(websocket.pipelineStage(self, wsContext), wsContext.response)
          socketio.wsConnecting(wsContext.request) foreach { _ =>
            socketioTransport = Some(WebSocket)
            log.debug("socketio connection of {} connected on websocket.", serverConnection.path)
          }
      }

    case UHttp.Upgraded =>
      enableHeartbeat()
      context.become(upgraded)
      log.debug("http connection of {} upgraded.", serverConnection.path)
  }

  def handleWebsocket: Receive = {
    case frame @ socketio.WsFrame(ok) =>
      log.debug("Got {}", frame)
  }

  def handleXrhpolling: Receive = {
    case req @ socketio.HttpGet(ok) =>
      resetCloseTimeout()
      socketioTransport = Some(XhrPolling)
      log.debug("socketio connection of {} GET {}", serverConnection.path, req.entity)

    case req @ socketio.HttpPost(ok) =>
      resetCloseTimeout()
      socketioTransport = Some(XhrPolling)
      log.debug("socketio connection of {} POST {}", serverConnection.path, req.entity)
  }

  def genericLogic: Receive

  private def closeConnectionActive() {
    if (soConnContext.sessionId != null && !connectionActiveClosed) {
      connectionActiveClosed = true
      resolver ! ConnectionActive.Closing(soConnContext.sessionId, soConnContext.serverConnection)
    }
  }

  override def postStop() {
    clearHeartbeat()
    clearCloseTimeout()
    closeConnectionActive()
  }

  def handleTerminate: Receive = {
    case x: Http.ConnectionClosed =>
      closeConnectionActive()
      context.stop(self)
      log.debug("http connection of {} stopped due to {}.", serverConnection.path, x)
    case Tcp.Closed => // may be triggered by the first socketio handshake http connection, which will always be droped.
      closeConnectionActive()
      context.stop(self)
      log.debug("http connection of {} stopped due to Tcp.Closed}.", serverConnection.path)
  }

  def clearHeartbeat() {
    heartbeatHandler foreach (_.cancel)
    heartbeatHandler = None
  }

  def enableHeartbeat() {
    if (heartbeatHandler.isEmpty || heartbeatHandler.nonEmpty && heartbeatHandler.get.isCancelled) {
      heartbeatHandler = Some(context.system.scheduler.schedule(heartbeatDelay.seconds, socketio.Settings.heartbeatInterval.seconds, self, socketio.SendHeartbeat))
      resetCloseTimeout()
    }
  }

  def resetCloseTimeout() {
    log.debug("started close-timeout, will close in {} seconds", socketio.Settings.CloseTimeout)
    closeTimeout foreach (_.cancel) // it better to confirm previous closeTimeout was cancled
    if (context != null) {
      closeTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds) {
        log.info("stoped due to close-timeout of {} seconds", socketio.Settings.CloseTimeout)
        clearHeartbeat()
        closeConnectionActive
        context.stop(self)
      })
    }
  }

  def clearCloseTimeout() {
    log.debug("cleared close-timeout")
    closeTimeout foreach (_.cancel)
    closeTimeout = None
  }

}
