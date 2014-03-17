package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Terminated
import scala.collection.concurrent
import akka.contrib.pattern.DistributedPubSubMediator.{Publish, Unsubscribe, SubscribeAck, Subscribe}

object LocalMediator {
  private var mediator: ActorRef = _
  def apply(system: ActorSystem): ActorRef = {
    if (mediator == null) {
      mediator = system.actorOf(Props(classOf[LocalMediator]), name = name)
    }
    mediator
  }

  private val topicToSubscitptions = concurrent.TrieMap[String, Set[ActorRef]]()

  val name = "localmediator"

}

class LocalMediator extends Actor with ActorLogging {
  import LocalMediator._

  def subscitptionsFor(topic: String): Set[ActorRef] = {
    topicToSubscitptions.getOrElseUpdate(topic, Set[ActorRef]())
  }

  def receive: Receive = {
    case x @ Subscribe(topic, subscitption) =>
      val subs = subscitptionsFor(topic)
      topicToSubscitptions(topic) = subs + subscitption
      context.watch(subscitption)
      sender() ! SubscribeAck(x)

    case Unsubscribe(topic, subscitption) =>
      topicToSubscitptions.get(topic) match {
        case Some(xs) =>
          val subs = xs - subscitption
          if (subs.isEmpty) {
            topicToSubscitptions -= topic
          } else {
            topicToSubscitptions(topic) = subs
          }

        case None =>
      }

    case Terminated(ref) =>
      var topicsToRemove = List[String]()
      for { (topic, xs) <- topicToSubscitptions } {
        val subs = xs - ref
        if (subs.isEmpty) {
          topicsToRemove ::= topic
        } else {
          topicToSubscitptions(topic) = subs
        }
      }
      topicToSubscitptions --= topicsToRemove

    case Publish(topic: String, msg: Any) =>
      topicToSubscitptions.get(topic) foreach { subs => subs foreach (_ ! msg) }
  }
}
