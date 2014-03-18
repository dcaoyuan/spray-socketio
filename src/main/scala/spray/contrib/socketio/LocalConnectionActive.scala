package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated

class LocalConnectionActive(val mediator: ActorRef) extends ConnectionActive with Actor with ActorLogging {
  import ConnectionActive._

  // have to call after log created
  enableCloseTimeout()

  def receive = working
}

class LocalConnectionActiveResolver(mediator: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  def receive = {
    case ConnectionActive.CreateSession(sessionId: String) =>
      context.child(sessionId) match {
        case Some(_) =>
        case None =>
          val connectActive = context.actorOf(Props(classOf[LocalConnectionActive], mediator), name = sessionId)
          context.watch(connectActive)
      }

    case cmd: ConnectionActive.Command =>
      context.child(cmd.sessionId) match {
        case Some(ref) => ref forward cmd
        case None      => log.warning("Failed to select actor {}", cmd.sessionId)
      }

    case Terminated(ref) =>
  }
}
