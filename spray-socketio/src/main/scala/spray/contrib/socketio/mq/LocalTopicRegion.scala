package spray.contrib.socketio.mq

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import java.net.URLEncoder

object LocalTopicRegion {
  def props(topicProps: Props) = Props(classOf[LocalTopicRegion], topicProps)
}

class LocalTopicRegion(topicProps: Props) extends Actor with ActorLogging {

  def receive = {
    case Terminated(ref) =>
    case msg             => deliverMessage(msg, sender())
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val (id, m) = Topic.idExtractor(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      val name = URLEncoder.encode(id, "utf-8")
      val entry = context.child(name).getOrElse {
        context.watch(context.actorOf(topicProps, name))
      }
      entry.tell(m, snd)
    }
  }
}
