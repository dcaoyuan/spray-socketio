package spray.contrib

import spray.http.HttpRequest
import spray.http.HttpResponse
import java.util.UUID
import spray.contrib.socketio.transport.SocketIOClient
import spray.contrib.socketio.transport.Transport
import spray.contrib.socketio.transport.WebSocket
import spray.contrib.socketio.transport.XhrPolling
import spray.http.HttpHeaders
import spray.http.HttpHeaders._
import spray.http.HttpMethods._
import spray.http.SomeOrigins
import spray.http.StatusCodes

package object socketio {
  // TODO config options
  val supportedTransports = List(WebSocket, XhrPolling).map(_.id).mkString(",")
  val heartbeatTimeout = 15
  val connectionClosingTimeout = 10

  object HandshakeRequest {

    def unapply(req: HttpRequest): Option[HttpResponse] = req match {
      case HttpRequest(GET, uri, headers, _, _) =>
        uri.path.toString.split("/") match {
          case Array("", "socket.io", protocalVersion) =>
            val origins = headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)

            val sessionId = UUID.randomUUID
            val entity = List(sessionId, heartbeatTimeout, connectionClosingTimeout, supportedTransports).mkString(":")

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

  def clientFor(req: HttpRequest): Option[SocketIOClient] = {
    req.uri.path.toString.split("/") match {
      case Array("", socketio, protocalVersion, transportId, sessionId) =>
        Transport.transportFor(transportId) map { transport =>
          new SocketIOClient(socketio, transport, sessionId, null)
        }
      case _ => None
    }
  }
}
