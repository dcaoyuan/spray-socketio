package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.StatusCode
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.http.HttpHeaders
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.Uri

trait SocketIOClientConnection extends Actor with ActorLogging {
  private var _connection: ActorRef = _
  /**
   * The actor which could receive frame directly. ie. by
   *   connection ! frame
   */
  def connection = _connection

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
            log.debug("socket.io connection opened")
            context.become(businessLogic orElse socketioLogic orElse closeLogic)
          case _ =>
        }
      } catch {
        case ex: Throwable =>
          log.warning(ex.getMessage, ex.getCause)
          connection ! CloseFrame(StatusCode.InternalError, ex.getMessage)
      }
  }

  def socketioLogic: Receive = {
    case TextFrame(payload) =>
      try {
        PacketParser(payload) foreach {
          case ConnectPacket(endpoint, args) => onConnected(endpoint, args)
          case DisconnectPacket(endpoint)    => onDisconnected(endpoint)
          case HeartbeatPacket               => connection ! TextFrame(HeartbeatPacket.render)
          case packet =>
            log.debug("Got {}", packet)
            onPacket(packet)
        }
      } catch {
        case ex: Throwable => log.warning(ex.getMessage, ex.getCause)
      }
  }

  def businessLogic: Receive

  def onOpen() {
    //connection ! TextFrame(ConnectPacket().render)
  }

  def onConnected(endpoint: String, args: Seq[(String, String)]) {

  }

  def onDisconnected(endpoint: String) {

  }

  def onPacket(packet: Packet)

}