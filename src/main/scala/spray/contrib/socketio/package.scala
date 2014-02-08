package spray.contrib

import spray.http.HttpRequest
import spray.http.HttpResponse
import java.util.UUID
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

  val NAMESPACE = "socket.io"

  object HandshakeRequest {

    def unapply(req: HttpRequest): Option[HttpResponse] = req match {
      case HttpRequest(GET, uri, headers, _, _) =>
        //log.info("socket.io handshake, path:{}, query:{}", uri.path, uri.query)

        uri.path.toString.split("/") match {
          case Array("", NAMESPACE, protocalVersion) =>
            val origins = headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)

            val sessionId = UUID.randomUUID
            val entity = List(sessionId, heartbeatTimeout, connectionClosingTimeout, supportedTransports).mkString(":")

            val handshakeResp = HttpResponse(
              status = StatusCodes.OK,
              entity = entity,
              headers = List(
                HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
                HttpHeaders.`Access-Control-Allow-Credentials`(true)))

            Some(handshakeResp)

          case _ => None
        }

      case _ => None
    }
  }

  def transportFor(req: HttpRequest): Option[Transport] = {
    req.uri.path.toString.split("/") match {
      case Array("", NAMESPACE, protocalVersion, transportId, sessionId) => Transport.transportFor(transportId)
      case _ => None
    }
  }

}
