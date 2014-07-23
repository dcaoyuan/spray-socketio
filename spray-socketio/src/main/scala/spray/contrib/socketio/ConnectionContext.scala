package spray.contrib.socketio

import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri
import scala.collection.immutable

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
class ConnectionContext(private var _sessionId: String = null, private var _query: Uri.Query = Uri.Query.Empty, private var _origins: Seq[HttpOrigin] = List()) extends Serializable {
  def sessionId = _sessionId
  private[socketio] def sessionId_=(sessionId: String) = {
    _sessionId = sessionId
    this
  }

  def query = _query
  private[socketio] def query_=(query: Uri.Query) = {
    _query = query
    this
  }

  def origins = _origins
  private[socketio] def origins_=(origins: Seq[HttpOrigin]) = {
    _origins = origins
    this
  }

  private var _transport: Transport = spray.contrib.socketio.transport.Empty
  def transport = _transport
  private[socketio] def transport_=(transport: Transport) = {
    _transport = transport
    this
  }

  private var _isConnected: Boolean = _
  def isConnected = _isConnected
  private[socketio] def isConnected_=(isConnected: Boolean) = {
    _isConnected = isConnected
    this
  }

  override def equals(other: Any) = {
    other match {
      case x: ConnectionContext => x.sessionId == this.sessionId && x.query == this.query && x.origins == this.origins
      case _ =>
        false
    }
  }

  override def toString(): String = {
    new StringBuilder().append(sessionId)
      .append(", query=").append(query)
      .append(", origins=").append(origins)
      .append(", isConnected=").append(isConnected)
      .toString
  }
}
