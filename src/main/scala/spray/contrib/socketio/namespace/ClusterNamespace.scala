package spray.contrib.socketio.namespace

import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import akka.contrib.pattern.DistributedPubSubExtension
import akka.pattern.ask
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio

/**
 * Namespace is refered to endpoint fo packets
 */
class ClusterNamespace(implicit val endpoint: String) extends Namespace {
  val mediator = DistributedPubSubExtension(context.system).mediator

  private var isMediatorSubscribed: Boolean = _
  def subscribeMediatorForNamespace(action: () => Unit) {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      mediator.ask(akka.contrib.pattern.DistributedPubSubMediator.Subscribe(namespace, self))(socketio.actorResolveTimeout).mapTo[SubscribeAck] onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to medietor on topic {}: {}", namespace, ex.getMessage)
      }
    } else {
      action()
    }
  }
}
