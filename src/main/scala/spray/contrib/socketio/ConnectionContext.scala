package spray.contrib.socketio

import akka.actor.ActorRef
import scala.collection.immutable.Queue
import spray.contrib.socketio.packet.Packet
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
 * connectionActive <1--1> connContext <1--1> transport <1--1> serverConnection
 */
class ConnectionContext(val sessionId: String, val query: Uri.Query, val origins: Seq[HttpOrigin], val namespaces: ActorRef) {
  var sendingPackets = Queue[Packet]()

  private var _transport: Transport = _
  def transport = _transport
  def bindTransport(transport: Transport) = {
    _transport = transport
    _transport.bindConnContext(this)
    this
  }

  private var _connectionActive: ActorRef = _
  def connectionActive = _connectionActive
  private[socketio] def bindConnectionActive(connectionActive: ActorRef) = {
    _connectionActive = connectionActive
    this
  }
}
