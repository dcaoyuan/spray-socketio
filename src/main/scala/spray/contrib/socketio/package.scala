package spray.contrib

import akka.actor.ActorRef
import akka.pattern._
import akka.pattern.ask
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.ConnectionActive.Processing
import spray.contrib.socketio.Namespace.AskConnectionContext
import spray.contrib.socketio.Namespace.Connecting
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.transport.WebSocket
import spray.contrib.socketio.transport.XhrPolling
import spray.http.HttpHeaders
import spray.http.HttpHeaders._
import spray.http.HttpMethods
import spray.http.HttpMethods._
import spray.http.HttpOrigin
import spray.http.HttpProtocols
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.SomeOrigins
import spray.http.StatusCodes
import spray.http.Uri

package object socketio {
  // TODO config options
  val supportedTransports = List(WebSocket, XhrPolling).map(_.ID).mkString(",")
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

  case class SoConnectingContext(serverConnection: ActorRef, namespaces: ActorRef, implicit val ec: ExecutionContext)

  def wsConnected(req: HttpRequest)(implicit soConnContext: SoConnectingContext): Option[Boolean] = {
    val query = req.uri.query
    val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
    req.uri.path.toString.split("/") match {
      case Array("", SOCKET_IO, protocalVersion, WebSocket.ID, sessionId) =>
        import soConnContext.ec
        val connecting = Namespace.Connecting(sessionId, query, origins, new WebSocket(soConnContext.serverConnection))
        for {
          connContextOpt <- soConnContext.namespaces.ask(connecting)(5.seconds).mapTo[Option[ConnectionContext]]
          connContext <- connContextOpt
        } {
          connContext.transport.asInstanceOf[WebSocket].sendPacket(ConnectPacket())
          connContext.connectionActive ! Processing
        }
        Some(true)
      case _ =>
        None
    }
  }

  /**
   * Test websocket frame under socketio
   */
  object WsFrame {
    def unapply(frame: TextFrame)(implicit soConnContext: SoConnectingContext): Option[Boolean] = frame match {
      case TextFrame(payload) =>
        import soConnContext.ec
        for {
          connContextOpt <- soConnContext.namespaces.ask(Namespace.AskConnectionContext(Right(soConnContext.serverConnection)))(5.seconds).mapTo[Option[ConnectionContext]]
          connContext <- connContextOpt
        } {
          connContext.transport.asInstanceOf[WebSocket].onPayload(soConnContext.serverConnection, payload)
        }
        Some(true)
      case _ => None
    }
  }

  /**
   * Test http get request under socketio
   */
  object HttpGet {
    def unapply(req: HttpRequest)(implicit soConnContext: SoConnectingContext): Option[Boolean] = req match {
      case HttpRequest(HttpMethods.GET, uri, _, _, HttpProtocols.`HTTP/1.1`) =>
        val query = req.uri.query
        val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
        uri.path.toString.split("/") match {
          case Array("", SOCKET_IO, protocalVersion, XhrPolling.ID, sessionId) =>
            import soConnContext.ec
            for {
              connContextOpt <- soConnContext.namespaces.ask(Namespace.AskConnectionContext(Left(sessionId)))(5.seconds).mapTo[Option[ConnectionContext]]
            } {
              connContextOpt match {
                case Some(connContext) =>
                  connContext.transport.asInstanceOf[XhrPolling].onGet(soConnContext.serverConnection)

                case None =>
                  val connecting = Namespace.Connecting(sessionId, query, origins, new XhrPolling)
                  for {
                    connContextOpt <- soConnContext.namespaces.ask(connecting)(5.seconds).mapTo[Option[ConnectionContext]]
                    connContext <- connContextOpt
                  } {
                    connContext.transport.asInstanceOf[XhrPolling].sendPacket(ConnectPacket())
                    connContext.transport.asInstanceOf[XhrPolling].onGet(soConnContext.serverConnection)
                    connContext.connectionActive ! Processing
                  }
              }
            }
            Some(true)
          case _ => None
        }
      case _ => None
    }
  }

  /**
   * Test http post request under socketio
   */
  object HttpPost {
    def unapply(req: HttpRequest)(implicit soConnContext: SoConnectingContext): Option[Boolean] = req match {
      case HttpRequest(HttpMethods.POST, uri, _, entity, HttpProtocols.`HTTP/1.1`) =>
        val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
        uri.path.toString.split("/") match {
          case Array("", SOCKET_IO, protocalVersion, XhrPolling.ID, sessionId) =>
            import soConnContext.ec
            for {
              connContextOpt <- soConnContext.namespaces.ask(Namespace.AskConnectionContext(Left(sessionId)))(5.seconds).mapTo[Option[ConnectionContext]]
              connContext <- connContextOpt
            } {
              connContext.transport.asInstanceOf[XhrPolling].onPost(soConnContext.serverConnection, entity.data.toByteString)
            }
            Some(true)
          case _ => None
        }
      case _ => None
    }
  }
}
