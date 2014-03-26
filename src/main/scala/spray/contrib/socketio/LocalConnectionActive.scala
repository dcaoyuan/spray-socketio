package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import scala.collection.concurrent
import akka.contrib.pattern.DistributedPubSubMediator.{ Publish, Unsubscribe, SubscribeAck, Subscribe }

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

object LocalMediator {
  private val topicToSubscitptions = concurrent.TrieMap[String, Set[ActorRef]]()
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

