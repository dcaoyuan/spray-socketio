package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Terminated
import spray.contrib.socketio

class LocalConnectionActive extends ConnectionActive with Actor with ActorLogging {
  import ConnectionActive._

  // have to call after log created
  enableCloseTimeout()

  val mediator = LocalMediator(context.system)

  def receive = working
}

object LocalConnectionActiveResolver {
  private var resolver: ActorRef = _
  def apply(system: ActorSystem): ActorRef = {
    if (resolver == null) {
      resolver = system.actorOf(Props(classOf[LocalConnectionActiveResolver]), name = ConnectionActive.shardName)
    }
    resolver
  }
}

class LocalConnectionActiveResolver extends Actor with ActorLogging {
  import context.dispatcher

  def receive = {
    case ConnectionActive.CreateSession(sessionId: String) =>
      context.child(sessionId) match {
        case Some(_) =>
        case None =>
          val connectActive = context.actorOf(Props(classOf[LocalConnectionActive]), name = sessionId)
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
