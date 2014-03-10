package spray.contrib.socketio.cluster

import akka.contrib.pattern.{ DistributedPubSubExtension, DistributedPubSubMediator }
import akka.pattern.ask
import spray.contrib.socketio.Namespace
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.runtime.universe._

/**
 * Namespace is refered to endpoint fo packets
 */
class ClusterNamespace(implicit val endpoint: String) extends Namespace {
  val mediator = DistributedPubSubExtension(context.system).mediator

  override def noticeSubscribe(endpoint: String): Future[Any] = {
    import context.dispatcher
    // will return akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
    mediator.ask(akka.contrib.pattern.DistributedPubSubMediator.Subscribe(endpoint, self))(100.seconds)
  }
}
