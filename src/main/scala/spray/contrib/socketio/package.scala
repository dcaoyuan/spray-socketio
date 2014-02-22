package spray.contrib

import akka.actor.ActorRef
import java.util.UUID
import spray.contrib.socketio.Transport
import spray.contrib.socketio.WebSocket
import spray.contrib.socketio.XhrPolling
import spray.http.HttpHeaders
import spray.http.HttpHeaders._
import spray.http.HttpMethods._
import spray.http.HttpOrigin
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.SomeOrigins
import spray.http.StatusCodes
import spray.http.Uri

package object socketio {
  // TODO config options
  val supportedTransports = List(WebSocket, XhrPolling).map(_.id).mkString(",")
  val heartbeatTimeout = 30 // seconds
  val closeTimeout = 60 // seconds
  val SOCKET_IO = "socket.io"

  case class HandshakeState(response: HttpResponse, sessionId: String, qurey: Uri.Query, origins: Seq[HttpOrigin])

  object HandshakeRequest {

    def unapply(req: HttpRequest): Option[HandshakeState] = req match {
      case HttpRequest(GET, uri, headers, _, _) =>
        uri.path.toString.split("/") match {
          case Array("", SOCKET_IO, protocalVersion) =>
            val origins = headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
            val originsHeaders = List(
              HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
              HttpHeaders.`Access-Control-Allow-Credentials`(true))

            val respHeaders = List(HttpHeaders.Connection("keep-alive")) ::: originsHeaders
            val sessionId = UUID.randomUUID.toString
            val respEntity = List(sessionId, heartbeatTimeout, closeTimeout, supportedTransports).mkString(":")

            val resp = HttpResponse(
              status = StatusCodes.OK,
              entity = respEntity,
              headers = respHeaders)

            Some(HandshakeState(resp, sessionId, uri.query, origins))

          case _ => None
        }

      case _ => None
  }
    }

  def contextFor(req: HttpRequest, serverConnection: ActorRef): Option[SocketIOContext] = {
    req.uri.path.toString.split("/") match {
      case Array("", SOCKET_IO, protocalVersion, transportId, sessionId) =>
        Transport.transportFor(transportId) map { transport => new SocketIOContext(transport, sessionId, serverConnection) }
      case _ =>
        None
    }
  }

  def isSocketIOConnecting(uri: Uri): Boolean = {
    uri.path.toString.split("/") match {
      case Array("", SOCKET_IO, protocalVersion, transportId, sessionId) => Transport.isSupported(transportId)
      case _ => false
    }
  }
}
