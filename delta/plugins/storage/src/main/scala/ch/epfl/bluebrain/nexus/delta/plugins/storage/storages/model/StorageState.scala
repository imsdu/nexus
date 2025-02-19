package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{schemas, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceScope}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant

/**
  * State for an existing storage
  *
  * @param id
  *   the id of the storage
  * @param project
  *   the project it belongs to
  * @param value
  *   additional fields to configure the storage
  * @param source
  *   the representation of the storage as posted by the subject
  * @param rev
  *   the current state revision
  * @param deprecated
  *   the current state deprecation status
  * @param createdAt
  *   the instant when the resource was created
  * @param createdBy
  *   the subject that created the resource
  * @param updatedAt
  *   the instant when the resource was last updated
  * @param updatedBy
  *   the subject that last updated the resource
  */
final case class StorageState(
    id: Iri,
    project: ProjectRef,
    value: StorageValue,
    source: Json,
    rev: Int,
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  def schema: ResourceRef = Latest(schemas.storage)

  override def types: Set[Iri] = value.tpe.types

  def storage: Storage =
    value match {
      case value: DiskStorageValue => DiskStorage(id, project, value, source)
      case value: S3StorageValue   => S3Storage(id, project, value, source)
    }

  def toResource: StorageResource =
    ResourceF(
      id = id,
      scope = ResourceScope("storages", project, id),
      rev = rev,
      types = value.tpe.types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = storage
    )
}

object StorageState {

  implicit def serializer: Serializer[Iri, StorageState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration = Serializer.circeConfiguration

    implicit val storageValueCodec: Codec.AsObject[StorageValue] = StorageValue.databaseCodec
    implicit val codec: Codec.AsObject[StorageState]             = deriveConfiguredCodec[StorageState]
    Serializer.dropNullsInjectType()
  }
}
