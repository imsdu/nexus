package ch.epfl.bluebrain.nexus.delta.sourcing.projections.model

import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

/**
  * Intent to restart a given projection by a user
  * @param name
  *   the name of the projection to restart
  * @param instant
  *   the instant the user performed the action
  * @param subject
  *   the user
  */
final case class ProjectionRestart(name: String, instant: Instant, subject: Subject)

object ProjectionRestart {

  val entityType: EntityType = EntityType("projection-restart")

  @nowarn("cat=unused")
  implicit val projectionRestartCodec: Codec[ProjectionRestart] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration = Serializer.circeConfiguration
    deriveConfiguredCodec[ProjectionRestart]
  }

}
