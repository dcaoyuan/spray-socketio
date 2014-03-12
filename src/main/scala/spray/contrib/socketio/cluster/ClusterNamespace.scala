package spray.contrib.socketio.cluster

import akka.contrib.pattern.{ DistributedPubSubExtension, DistributedPubSubMediator }
import akka.pattern.ask
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio.Namespace
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.runtime.universe._

/**
 * Namespace is refered to endpoint fo packets
 */
class ClusterNamespace(implicit val endpoint: String) extends Namespace {
  val mediator = DistributedPubSubExtension(context.system).mediator
  private var isSubscribed: Boolean = _

  override def noticeSubscribe(endpoint: String) {
    if (!isSubscribed) {
      import context.dispatcher
      // will return akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
      mediator.ask(akka.contrib.pattern.DistributedPubSubMediator.Subscribe(endpoint, self))(100.seconds).onComplete {
        case Success(ack) => isSubscribed = true
        case Failure(ex)  => log.warning("Failed to subscribe to medietor on topic {}: {}", endpoint, ex.getMessage)
      }
    }
  }
}
