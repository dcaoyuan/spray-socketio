package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import java.net.URLEncoder

object LocalNamespaceRegion {
  def props(mediator: ActorRef) = Props(classOf[LocalNamespaceRegion], mediator)
}

class LocalNamespaceRegion(mediator: ActorRef) extends Actor with ActorLogging {

  def receive = {
    case Terminated(ref) =>
    case msg             => deliverMessage(msg, sender())
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val (id, m) = Namespace.idExtractor(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      val name = URLEncoder.encode(id, "utf-8")
      val entry = context.child(name).getOrElse {
        context.watch(context.actorOf(Namespace.props(mediator), name))
      }
      entry.tell(m, snd)
    }
  }
}
