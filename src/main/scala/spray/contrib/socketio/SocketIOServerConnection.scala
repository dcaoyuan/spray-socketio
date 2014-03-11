package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.contrib.socketio

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
trait SocketIOServerConnection extends Actor with ActorLogging {
  def serverConnection: ActorRef
  def resolver: ConnectionActiveResolver

  implicit val soConnContext = new socketio.SoConnectingContext(null, serverConnection, log, resolver, context.dispatcher)

  import context.dispatcher

  def receive = socketioHandshake orElse websocketConnecting orElse xrhpollingConnecting orElse genericLogic orElse closeLogic

  def closeLogic: Receive = {
    case x: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("{}: http connection stopped due to {}.", serverConnection.path, x)
  }

  def socketioHandshake: Receive = {
    case socketio.HandshakeRequest(ok) =>
      log.debug("{}: socketio handshaked.", serverConnection.path)
  }

  def websocketConnecting: Receive = {
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
      context.become(websocketLogic orElse closeLogic)
  }

  def websocketLogic: Receive = {
    case frame @ socketio.WsFrame(ok) =>
      log.debug("Got {}", frame)
  }

  def xrhpollingConnecting: Receive = {
    case req @ socketio.HttpGet(ok) =>
      log.debug("{}: socketio GET {}", serverConnection.path, req.entity)

    case req @ socketio.HttpPost(ok) =>
      log.debug("{}: socketio POST {}", serverConnection.path, req.entity)
  }

  def genericLogic: Receive

}
