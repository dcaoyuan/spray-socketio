package spray.contrib.socketio

import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

/**
 * Extension that holds a sessionId that is assigned as a random `Int`.
 */
object SessionIdExtension extends ExtensionId[SessionIdExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SessionIdExtension = super.get(system)

  override def lookup = SessionIdExtension

  override def createExtension(system: ExtendedActorSystem): SessionIdExtension = new SessionIdExtension(system)
}

class SessionIdExtension(val system: ExtendedActorSystem) extends Extension {
  val addressUid: Int = ThreadLocalRandom.current.nextInt()
}