package spray.contrib.socketio.namespace

import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.contrib.pattern.DistributedPubSubExtension
import akka.pattern.ask
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio

object ClusterNamespace {
  def apply(system: ActorSystem)(endpoint: String): Future[ActorRef] = {
    val namespace = socketio.namespaceFor(endpoint)
    val path = "/user/" + namespace
    import system.dispatcher
    system.actorSelection("/user/" + namespace).resolveOne(socketio.actorResolveTimeout).recover {
      case _: Throwable => system.actorOf(Props(classOf[ClusterNamespace], endpoint), name = namespace)
    }.mapTo[ActorRef]
  }
}

class ClusterNamespace(implicit val endpoint: String) extends Namespace {
  val mediator = DistributedPubSubExtension(context.system).mediator

  private var isMediatorSubscribed: Boolean = _
  def subscribeMediatorForEndpoint(action: () => Unit) {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      mediator.ask(akka.contrib.pattern.DistributedPubSubMediator.Subscribe(socketio.topicFor(endpoint, ""), self))(socketio.actorResolveTimeout).mapTo[SubscribeAck] onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to medietor on topic {}: {}", socketio.topicFor(endpoint, ""), ex.getMessage)
      }
    } else {
      action()
    }
  }
}
