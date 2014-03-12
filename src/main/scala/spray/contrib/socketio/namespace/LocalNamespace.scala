package spray.contrib.socketio.namespace

import akka.pattern.ask
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio
import spray.contrib.socketio.LocalMediator
import scala.concurrent.duration._

class LocalNamespace(implicit val endpoint: String) extends Namespace {
  val mediator = LocalMediator(context.system)

  private var isMediatorSubscribed: Boolean = _
  def subscribeMediator(endpoint: String)(action: () => Unit) = {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      mediator.ask(LocalMediator.Subscribe(endpoint, self))(socketio.actorResolveTimeout.seconds) onComplete {
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