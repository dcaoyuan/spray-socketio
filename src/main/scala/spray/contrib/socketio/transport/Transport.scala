package spray.contrib.socketio.transport

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.event.Logging
import akka.util.ByteString
import org.parboiled2.ParseError
import spray.can.Http
import spray.can.websocket.FrameCommand
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionActive.SendPackets
import spray.contrib.socketio.ConnectionActive.WriteMultiple
import spray.contrib.socketio.ConnectionActive.WriteSingle
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.Namespace
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.http.ContentType
import spray.http.HttpEntity
import spray.http.HttpHeaders
import spray.http.HttpResponse
import spray.http.MediaTypes
import spray.http.SomeOrigins
import spray.json.JsValue

object Transport {
  trait Id { def ID: String }

  val transportIds = Set(
    XhrPolling,
    XhrMultipart,
    HtmlFile,
    WebSocket,
    FlashSocket,
    JsonpPolling).map(_.ID)

  def isSupported(id: String) = transportIds.contains(id)
}

/**
 * We should keep none states in Transport, if there are some common states, just keep in ConnectionContext
 * Specically, for websocket, we'll keep serverConnection
 */
trait Transport {
  def system: ActorSystem
  protected val log = Logging.getLogger(system, this)

  private var _connContext: ConnectionContext = _
  def connContext = _connContext
  def bindConnContext(connContext: ConnectionContext) = {
    _connContext = connContext
    this
  }

  private def properEndpoint(endpoint: String) = if (endpoint == Namespace.DEFAULT_NAMESPACE) "" else endpoint

  def sendMessage(msg: String, endpoint: String) {
    val packet = MessagePacket(-1L, false, properEndpoint(endpoint), msg)
    sendPacket(packet)
  }

  def sendJson(json: JsValue, endpoint: String) {
    val packet = JsonPacket(-1L, false, properEndpoint(endpoint), json)
    sendPacket(packet)
  }

  def sendEvent(name: String, args: List[JsValue], endpoint: String) {
    val packet = EventPacket(-1L, false, properEndpoint(endpoint), name, args)
    sendPacket(packet)
  }

  def sendPacket(packets: Packet*)

  protected[socketio] def write(serverConnection: ActorRef, payload: String)

  protected def onPayload(serverConnection: ActorRef, payload: ByteString) {
    try {
      val packets = PacketParser(payload)
      packets foreach { connContext.namespaces ! Namespace.OnPacket(_, connContext.sessionId) }
    } catch {
      case ex: ParseError => log.error(ex, "Error in parsing packet: {}" + ex.getMessage)
    }
  }

}

object WebSocket extends Transport.Id {
  val ID = "websocket"
}
final case class WebSocket(system: ActorSystem, connection: ActorRef) extends Transport {

  /**
   * Override to public method
   */
  override def onPayload(serverConnection: ActorRef, payload: ByteString) {
    super.onPayload(serverConnection, payload)
  }

  override def sendPacket(packets: Packet*) {
    connContext.connectionActive ! SendPackets(packets)
    connContext.connectionActive ! WriteMultiple(connection)
  }

  protected[socketio] def write(serverConnection: ActorRef, payload: String) {
    serverConnection ! FrameCommand(TextFrame(ByteString(payload)))
  }
}

object XhrPolling extends Transport.Id {
  val ID = "xhr-polling"
}
final case class XhrPolling(system: ActorSystem) extends Transport {
  def onGet(serverConnection: ActorRef) {
    connContext.connectionActive ! WriteSingle(serverConnection, isSendingNoopWhenEmpty = true)
  }

  def onPost(serverConnection: ActorRef, payload: ByteString) {
    // response an empty entity to release POST before message processing
    write(serverConnection, "")
    onPayload(serverConnection, payload)
  }

  def sendPacket(packets: Packet*) {
    connContext.connectionActive ! SendPackets(packets)
  }

  protected[socketio] def write(connection: ActorRef, payload: String) {
    val originsHeaders = List(
      HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(connContext.origins)),
      HttpHeaders.`Access-Control-Allow-Credentials`(true))
    val headers = List(HttpHeaders.Connection("keep-alive")) ::: originsHeaders
    connection ! Http.MessageCommand(HttpResponse(headers = headers, entity = HttpEntity(ContentType(MediaTypes.`text/plain`), payload)))
  }
}

object XhrMultipart extends Transport.Id {
  val ID = "xhr-multipart"
}
final case class XhrMultipart(system: ActorSystem) extends Transport {
  def sendPacket(packets: Packet*) {
    // TODO
  }

  protected[socketio] def write(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

object HtmlFile extends Transport.Id {
  val ID = "htmlfile"
}
final case class HtmlFile(system: ActorSystem) extends Transport {
  def sendPacket(packets: Packet*) {
    // TODO
  }
  protected[socketio] def write(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

object FlashSocket extends Transport.Id {
  val ID = "flashsocket"
}
final case class FlashSocket(system: ActorSystem) extends Transport {
  def sendPacket(packets: Packet*) {
    // TODO
  }
  protected[socketio] def write(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

object JsonpPolling extends Transport.Id {
  val ID = "jsonp-polling"
}
final case class JsonpPolling(system: ActorSystem) extends Transport {
  def sendPacket(packets: Packet*) {
    // TODO
  }
  protected[socketio] def write(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

