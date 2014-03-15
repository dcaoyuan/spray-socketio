package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import org.parboiled2.ParseError
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.StatusCode
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.packet.AckPacket
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DataPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.http.HttpHeaders
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.Uri

object SocketIOClientConnection {
  type AckPostAction = Any => Unit
  final case class SendPacket(packet: Packet)
  final case class SendPacketWithAck(packet: DataPacket, ackAction: AckPostAction = _ => ())
}
trait SocketIOClientConnection extends Actor with ActorLogging {
  import SocketIOClientConnection._

  private var _connection: ActorRef = _
  /**
   * The actor which could receive frame directly. ie. by
   *   connection ! frame
   */
  def connection = _connection

  private var idToAckAction = Map[Long, AckPostAction]()

  def receive = handshaking orElse closeLogic

  def closeLogic: Receive = {
    case ev: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("Connection closed on event: {}", ev)
  }

  def handshaking: Receive = {
    case Http.Connected(remoteAddress, localAddress) =>
      val host = remoteAddress.getHostName
      val port = remoteAddress.getPort
      val headers = List(HttpHeaders.Host(host, port))
      val authrity = Uri.Authority(Uri.NamedHost(host), port)
      val uri = Uri("http", Uri.Authority(Uri.NamedHost(host), port), Uri.Path("/" + socketio.SOCKET_IO + "/1/"))
      val socketioHandshake = HttpRequest(uri = uri, headers = headers)
      sender() ! socketioHandshake
      log.debug("Sent socket.io handshake request: {}", socketioHandshake)

    case socketio.HandshakeResponse(socketio.HandshakeContext(response, sessionId, heartbeatTimeout, closeTimeout)) =>
      val wsUpgradeRequest = websocket.basicHandshakeRepuset("/" + socketio.SOCKET_IO + "/1/websocket/" + sessionId)
      val upgradePipelineStage = { response: HttpResponse =>
        response match {
          case websocket.HandshakeResponse(state) =>
            state match {
              case wsFailure: websocket.HandshakeFailure => None
              case wsContext: websocket.HandshakeContext => Some(websocket.clientPipelineStage(self, wsContext))
            }
        }
      }
      sender() ! UHttp.UpgradeClient(upgradePipelineStage, wsUpgradeRequest)

    case UHttp.Upgraded =>
      // this is the proper actor that could receive frame sent to it directly
      // @see WebSocketFrontend#receiverRef
      _connection = sender()
      connection ! TextFrame(ConnectPacket().render)

    case TextFrame(payload) =>
      try {
        PacketParser(payload).headOption match {
          case Some(ConnectPacket(_, _)) =>
            onOpen()
            context.become(businessLogic orElse socketioLogic orElse closeLogic)
          case _ =>
        }
      } catch {
        case ex: ParseError =>
          log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
          connection ! CloseFrame(StatusCode.InternalError, "Invalide socket.io packet")
        case ex: Throwable =>
          log.warning("Exception during parse socket.io packet {}", ex.getMessage)
      }
  }

  def socketioLogic: Receive = {
    case TextFrame(payload) =>
      try {
        PacketParser(payload) foreach {
          case ConnectPacket(endpoint, args) => onConnected(endpoint, args)
          case DisconnectPacket(endpoint)    => onDisconnected(endpoint)
          case HeartbeatPacket               => connection ! TextFrame(HeartbeatPacket.utf8String)

          case AckPacket(id, args) =>
            idToAckAction.get(id) foreach { _(args) }
            idToAckAction -= id
            onAck(id, args)

          case packet =>
            log.debug("Got {}", packet)
            onPacket(packet)
        }
      } catch {
        case ex: ParseError =>
          log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
          connection ! CloseFrame(StatusCode.InternalError, "Invalide socket.io packet")
        case ex: Throwable =>
          log.warning("Exception during parse socket.io packet {}", ex.getMessage)
      }

    // -- sending logic

    case SendPacket(packet) => connection ! TextFrame(packet.render)

    case SendPacketWithAck(packet, ackAction) =>
      idToAckAction += (packet.id -> ackAction)
      connection ! TextFrame(packet.render)
  }

  def businessLogic: Receive

  def onOpen() {

  }

  def onConnected(endpoint: String, args: Seq[(String, String)]) {

  }

  def onDisconnected(endpoint: String) {

  }

  def onAck(id: Long, args: String) {

  }

  def onPacket(packet: Packet)

}
