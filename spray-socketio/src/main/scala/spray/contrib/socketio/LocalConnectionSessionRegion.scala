package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Terminated
import scala.collection.concurrent

object LocalConnectionSessionRegion {
  def props(connectionSessionProps: Props) = Props(classOf[LocalConnectionSessionRegion], connectionSessionProps)
}

class LocalConnectionSessionRegion(connectionSessionProps: Props) extends Actor with ActorLogging {

  def receive = {
    case ConnectionSession.CreateSession(sessionId: String) =>
      context.child(sessionId) match {
        case Some(_) =>
        case None =>
          val connectSession = context.actorOf(connectionSessionProps, name = sessionId)
          context.watch(connectSession)
      }

    case cmd: ConnectionSession.Command =>
      context.child(cmd.sessionId) match {
        case Some(ref) => ref forward cmd
        case None      => log.warning("Failed to select actor {}", cmd.sessionId)
      }

    case Terminated(ref) =>
  }
}

