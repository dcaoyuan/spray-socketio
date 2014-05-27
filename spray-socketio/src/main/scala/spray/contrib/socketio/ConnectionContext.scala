package spray.contrib.socketio

import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri

/**
 * Socket.IO has built-in support for multiple channels of communication
 * (which we call "multiple sockets"). Each socket is identified by an endpoint
 * (can be omitted).
 *
 * During connecting handshake (1::), endpoint is "", the default endpoint.
 * The client may then send ConnectPacket with endpoint (1::/endp1) and
 * (1::/endp2) etc to use the same sender-context pair as multiple sockets.
 * @See Namespace
 *
 * @Note let this context not to be final, so business application can store more
 * states in it.
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
class ConnectionContext(val sessionId: String, val query: Uri.Query, val origins: Seq[HttpOrigin]) extends Serializable {
  private var _transport: Transport = _
  def transport = _transport
  def bindTransport(transport: Transport) = {
    _transport = transport
    this
  }

  override def equals(x: Any) = {
    x match {
      case x: ConnectionContext =>
        x.sessionId == this.sessionId && x.query == this.query && x.origins == this.origins
      case _ => false
    }
  }

  override def toString(): String = sessionId + " " + query + " " + origins
}
