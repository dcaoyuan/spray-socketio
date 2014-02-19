package spray.contrib

import akka.actor.ActorRef
import java.util.UUID
import spray.contrib.socketio.transport.Transport
import spray.contrib.socketio.transport.WebSocket
import spray.contrib.socketio.transport.XhrPolling
import spray.http.HttpHeaders
import spray.http.HttpHeaders._
import spray.http.HttpMethods._
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.SomeOrigins
import spray.http.StatusCodes
import spray.http.Uri

package object socketio {
  // TODO config options
  val supportedTransports = List(WebSocket, XhrPolling).map(_.id).mkString(",")
  val heartbeatTimeout = 15 // seconds
  val closeTimeout = 30 // seconds

  object HandshakeRequest {

    def unapply(req: HttpRequest): Option[HttpResponse] = req match {
      case HttpRequest(GET, uri, headers, _, _) =>
        uri.path.toString.split("/") match {
          case Array("", "socket.io", protocalVersion) =>
            val origins = headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)

            val sessionId = UUID.randomUUID.toString
            Namespace.namespaces ! Namespace.Session(sessionId)

            val entity = List(sessionId, heartbeatTimeout, closeTimeout, supportedTransports).mkString(":")

            val resp = HttpResponse(
              status = StatusCodes.OK,
              entity = entity,
              headers = List(
                HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
                HttpHeaders.`Access-Control-Allow-Credentials`(true)))

            Some(resp)

          case _ => None
        }

      case _ => None
    }
  }

  def soContextFor(uri: Uri, sender: ActorRef): Option[SocketIOContext] = {
    uri.path.toString.split("/") match {
      case Array("", namespace, protocalVersion, transportId, sessionId) =>
        Transport.transportFor(transportId) map { transport => new SocketIOContext(transport, sessionId, sender) }
      case _ =>
        None
    }
  }

  def isSocketIOConnecting(uri: Uri): Boolean = {
    uri.path.toString.split("/") match {
      case Array("", namespace, protocalVersion, transportId, sessionId) => Transport.isSupported(transportId)
      case _ => false
    }
  }
}
