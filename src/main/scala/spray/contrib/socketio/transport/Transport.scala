package spray.contrib.socketio.transport

import akka.actor.ActorRef
import akka.util.ByteString
import spray.can.Http
import spray.can.websocket.FrameCommand
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.NoopPacket
import spray.contrib.socketio.packet.Packet
import spray.http.ContentType
import spray.http.HttpEntity
import spray.http.HttpHeaders
import spray.http.HttpResponse
import spray.http.MediaTypes
import spray.http.SomeOrigins
import scala.collection.immutable

object Transport {
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
  def ID: String

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet]

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String)

  /**
   * It seems XHR-Pollong client does not support multile packets.
   */
  protected[socketio] def writeMultiple(connContext: ConnectionContext, transportConnection: ActorRef, _pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    var pendingPackets = _pendingPackets
    if (pendingPackets.isEmpty) {
      // do nothing
    } else if (pendingPackets.tail.isEmpty) {
      val head = pendingPackets.head
      pendingPackets = pendingPackets.tail
      val payload = head.render.utf8String
      write(connContext, transportConnection, payload)
    } else {
      var totalLength = 0
      val sb = new StringBuilder()
      var prev: Packet = null
      while (pendingPackets.nonEmpty) {
        val curr = pendingPackets.head
        curr match {
          case NoopPacket | HeartbeatPacket if curr == prev => // keep one is enough
          case _ =>
            val msg = curr.render.utf8String
            totalLength += msg.length
            sb.append('\ufffd').append(msg.length.toString).append('\ufffd').append(msg)
        }
        pendingPackets = pendingPackets.tail
        prev = curr
      }
      val payload = sb.toString
      write(connContext, transportConnection, payload)
    }

    pendingPackets
  }

  protected[socketio] def writeSingle(connContext: ConnectionContext, transportConnection: ActorRef, isSendingNoopWhenEmpty: Boolean, _pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    var pendingPackets = _pendingPackets
    if (pendingPackets.isEmpty) {
      if (isSendingNoopWhenEmpty) {
        write(connContext, transportConnection, NoopPacket.utf8String)
      }
    } else {
      val head = pendingPackets.head
      pendingPackets = pendingPackets.tail
      val payload = head.render.utf8String
      //println("Write {}, to {}", payload, transportConnection)
      write(connContext, transportConnection, payload)
    }
    pendingPackets
  }

}

object WebSocket extends Transport {
  val ID = "websocket"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    writeMultiple(connContext, transportConnection, pendingPackets)
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
    transportConnection ! FrameCommand(TextFrame(ByteString(payload)))
  }
}

object XhrPolling extends Transport {
  val ID = "xhr-polling"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    // will wait for onGet
    pendingPackets
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
    val originsHeaders = List(
      HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(connContext.origins)),
      HttpHeaders.`Access-Control-Allow-Credentials`(true))
    val headers = List(HttpHeaders.Connection("keep-alive")) ::: originsHeaders
    transportConnection ! Http.MessageCommand(HttpResponse(headers = headers, entity = HttpEntity(ContentType(MediaTypes.`text/plain`), payload)))
  }

}

object XhrMultipart extends Transport {
  val ID = "xhr-multipart"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    // TODO
    pendingPackets
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
    // TODO
  }
}

object HtmlFile extends Transport {
  val ID = "htmlfile"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    // TODO
    pendingPackets
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
    // TODO
  }
}

object FlashSocket extends Transport {
  val ID = "flashsocket"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    // TODO
    pendingPackets
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
    // TODO
  }
}

object JsonpPolling extends Transport {
  val ID = "jsonp-polling"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    // TODO
    pendingPackets
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
    // TODO
  }
}

