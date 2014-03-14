package spray.contrib.socketio.namespace

import akka.pattern.ask
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio
import spray.contrib.socketio.LocalMediator
import spray.contrib.socketio.LocalMediator.SubscribeAck

class LocalNamespace(implicit val endpoint: String) extends Namespace {
  val mediator = LocalMediator(context.system)

  private var isMediatorSubscribed: Boolean = _
  def subscribeMediatorForNamespace(action: () => Unit) = {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      mediator.ask(LocalMediator.Subscribe(namespace, self))(socketio.actorResolveTimeout).mapTo[SubscribeAck] onComplete {
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