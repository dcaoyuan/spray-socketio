package spray.contrib.socketio.namespace

import akka.contrib.pattern.{ DistributedPubSubExtension, DistributedPubSubMediator }
import akka.pattern.ask
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio
import scala.concurrent.duration._

/**
 * Namespace is refered to endpoint fo packets
 */
class ClusterNamespace(implicit val endpoint: String) extends Namespace {
  val mediator = DistributedPubSubExtension(context.system).mediator

  private var isMediatorSubscribed: Boolean = _
  def subscribeMediator(endpoint: String)(action: () => Unit) {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      // will return akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
      mediator.ask(akka.contrib.pattern.DistributedPubSubMediator.Subscribe(endpoint, self))(socketio.actorResolveTimeout.seconds) onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to medietor on topic {}: {}", endpoint, ex.getMessage)
      }
    } else {
      action()
    }
  }
}
