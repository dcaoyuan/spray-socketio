package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Terminated
import scala.collection.concurrent

object LocalMediator {
  private val topicToSubscitptions = concurrent.TrieMap[String, Set[ActorRef]]()

  val name = "localmediator"

  final case class Subscribe(topic: String, subscitption: ActorRef)
  final case class Unsubscribe(topic: String, subscitption: ActorRef)
  final case class Publish(topic: String, msg: Any)
  case object SubscribeAck

  private var mediator: ActorRef = _
  def apply(system: ActorSystem): ActorRef = {
    if (mediator == null) {
      mediator = system.actorOf(Props(classOf[LocalMediator]), name = name)
    }
    mediator
  }
}

class LocalMediator extends Actor with ActorLogging {
  import LocalMediator._

  def subscitptionsFor(topic: String): Set[ActorRef] = {
    topicToSubscitptions.getOrElseUpdate(topic, Set[ActorRef]())
  }

  def receive: Receive = {
    case Subscribe(topic, subscitption) =>
      val subs = subscitptionsFor(topic)
      topicToSubscitptions(topic) = subs + subscitption
      context.watch(subscitption)
      sender() ! SubscribeAck

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
