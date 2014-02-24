package spray.contrib.socketio.transport

import akka.actor.ActorRef
import akka.util.ByteString
import org.parboiled2.ParseError
import spray.can.Http
import spray.can.websocket.FrameCommand
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.Namespace
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.NoopPacket
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

  def sendPacket(packets: Packet*) {
    //log.debug("{}: enqueue {}", self, packets)
    packets foreach { packet => connContext.sendingPackets = connContext.sendingPackets.enqueue(packet) }
    println(connContext.sendingPackets)
  }

  protected def onPayload(serverConnection: ActorRef, payload: ByteString) {
    try {
      val packets = PacketParser(payload)
      packets foreach { connContext.namespaces ! Namespace.OnPacket(_, connContext.sessionId) }
    } catch {
      case ex: ParseError => //log.error(ex, "Error in parsing packet: {}" + ex.getMessage)
    }
  }

  protected def dispatch(serverConnection: ActorRef, payload: String)

  protected def dispatch(serverConnection: ActorRef) {
    if (connContext.sendingPackets.isEmpty) {
      // do nothing
    } else if (connContext.sendingPackets.tail.isEmpty) {
      val head = connContext.sendingPackets.head
      connContext.sendingPackets = connContext.sendingPackets.tail
      val payload = head.render.utf8String
      //println("Dispatch ", payload, ", to", serverConnection)
      dispatch(serverConnection, payload)
    } else {
      var totalLength = 0
      val sb = new StringBuilder()
      var prev: Packet = null
      while (connContext.sendingPackets.nonEmpty) {
        val curr = connContext.sendingPackets.head
        curr match {
          case NoopPacket | HeartbeatPacket if curr == prev => // keep one is enough
          case _ =>
            val msg = curr.render.utf8String
            totalLength += msg.length
            sb.append('\ufffd').append(msg.length.toString).append('\ufffd').append(msg)
        }
        connContext.sendingPackets = connContext.sendingPackets.tail
        prev = curr
      }
      val payload = sb.toString
      //println("Dispatch ", payload, ", to", serverConnection)
      dispatch(serverConnection, payload)
    }
  }

}

object WebSocket extends Transport.Id {
  val ID = "websocket"
}
final case class WebSocket(connection: ActorRef) extends Transport {

  override def onPayload(serverConnection: ActorRef, payload: ByteString) {
    super.onPayload(serverConnection, payload)
  }

  override def sendPacket(packets: Packet*) {
    super.sendPacket(packets: _*)
    dispatch(connection)
  }

  def dispatch(serverConnection: ActorRef, payload: String) {
    serverConnection ! FrameCommand(TextFrame(ByteString(payload)))
  }
}

object XhrPolling extends Transport.Id {
  val ID = "xhr-polling"
}
class XhrPolling extends Transport {
  def onGet(serverConnection: ActorRef) {
    if (connContext.sendingPackets.isEmpty) {
      sendPacket(NoopPacket)
    }
    dispatch(serverConnection)
  }

  def onPost(serverConnection: ActorRef, payload: ByteString) {
    // response an empty entity to release POST before message processing
    dispatch(serverConnection, "")
    onPayload(serverConnection, payload)
  }

  protected def dispatch(connection: ActorRef, payload: String) {
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
class XhrMultipart extends Transport {
  protected def dispatch(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

object HtmlFile extends Transport.Id {
  val ID = "htmlfile"
}
class HtmlFile extends Transport {
  protected def dispatch(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

object FlashSocket extends Transport.Id {
  val ID = "flashsocket"
}
class FlashSocket extends Transport {
  protected def dispatch(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

object JsonpPolling extends Transport.Id {
  val ID = "jsonp-polling"
}
class JsonpPolling extends Transport {
  protected def dispatch(serverConnection: ActorRef, payload: String) {
    // TODO
  }
}

