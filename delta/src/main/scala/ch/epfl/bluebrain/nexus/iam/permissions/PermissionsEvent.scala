package ch.epfl.bluebrain.nexus.iam.permissions

import java.time.Instant

import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.types.{Identity, Permission}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.Contexts._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import io.circe.Encoder
import io.circe.generic.extras.Configuration

import scala.annotation.nowarn

/**
  * Enumeration of Permissions event types.
  */
sealed trait PermissionsEvent extends Product with Serializable {

  /**
    * @return the revision this event generated
    */
  def rev: Long

  /**
    * @return the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject
}

object PermissionsEvent {

  /**
    * A witness to a collection of permissions appended to the set.
    *
    * @param rev         the revision this event generated
    * @param permissions the collection of permissions appended to the set
    * @param instant     the instant when the event was emitted
    * @param subject     the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsAppended(
      rev: Long,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to a collection of permissions subtracted from the set.
    *
    * @param rev         the revision this event generated
    * @param permissions the collection of permissions subtracted from the set
    * @param instant     the instant when the event was emitted
    * @param subject     the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsSubtracted(
      rev: Long,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to the permission set being replaced.
    *
    * @param rev         the revision this event generated
    * @param permissions the new set of permissions that replaced the previous set
    * @param instant     the instant when the event was emitted
    * @param subject     the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsReplaced(
      rev: Long,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to the permission set being deleted (emptied).
    *
    * @param rev     the revision this event generated
    * @param instant the instant when the event was emitted
    * @param subject the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsDeleted(
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  object JsonLd {
    import io.circe.generic.extras.semiauto._

    @nowarn("cat=unused")
    implicit def permissionsEventEncoder(implicit http: HttpConfig): Encoder[Event] = {
      implicit val config: Configuration            = Configuration.default
        .withDiscriminator("@type")
        .copy(transformMemberNames = {
          case "rev"     => "_rev"
          case "instant" => "_instant"
          case "subject" => "_subject"
          case other     => other
        })
      implicit val subjectEncoder: Encoder[Subject] = Identity.subjectIdEncoder
      deriveConfiguredEncoder[Event]
        .mapJson { json =>
          json
            .addContext(iamCtxUri)
            .addContext(resourceCtxUri)
        }
    }
  }
}
