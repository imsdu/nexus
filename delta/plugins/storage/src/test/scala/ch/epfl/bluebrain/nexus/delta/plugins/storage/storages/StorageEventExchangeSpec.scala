package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.StorageDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Storage, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class StorageEventExchangeSpec
    extends AbstractDBSpec
    with Matchers
    with TryValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with StorageFixtures
    with RemoteContextResolutionFixture {

  implicit private val scheduler: Scheduler = Scheduler.global
  implicit val ec: ExecutionContext         = system.dispatcher

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val storagesConfig = StoragesConfig(aggregate, keyValueStore, pagination, indexing, config)

  private val allowedPerms = Set(
    diskFields.readPermission.value,
    diskFields.writePermission.value,
    s3Fields.readPermission.value,
    s3Fields.writePermission.value,
    remoteFields.readPermission.value,
    remoteFields.writePermission.value
  )

  private val storages = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    perms            <- PermissionsDummy(allowedPerms)
    resolverCtx       = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    storages         <- Storages(storagesConfig, eventLog, resolverCtx, perms, orgs, projects, (_, _) => IO.unit, crypto)
  } yield storages).accepted

  "A StorageEventExchanges" should {
    val id           = iri"http://localhost/${genString()}"
    val sourceSecret = s3FieldsJson
    val source       = Storage.encryptSource(sourceSecret, crypto).success.value
    val tag          = TagLabel.unsafe("tag")

    val exchange = new StorageEventExchange(storages)(baseUri, crypto)

    val resRev1         = storages.create(id, project.ref, sourceSecret).accepted
    val resRev2         = storages.tag(id, project.ref, tag, 1L, 1L).accepted
    val deprecatedEvent = StorageDeprecated(id, project.ref, DiskStorageType, 1, Instant.EPOCH, subject)

    "return the latest resource state from the event" in {
      val result = exchange.toResource(deprecatedEvent, None).accepted.value
      result.value.toSource shouldEqual source
      result.value.toResource shouldEqual resRev2
      result.metadata.value shouldEqual Metadata(DigestAlgorithm.default)
    }

    "return the latest resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value
      result.value.toSource shouldEqual source
      result.value.toResource shouldEqual resRev1
      result.metadata.value shouldEqual Metadata(DigestAlgorithm.default)
    }

    "return the encoded event" in {
      val result = exchange.toJsonLdEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder.compact(result.value).accepted.json shouldEqual
        json"""{
          "@context" : ["${Vocabulary.contexts.metadata}", "${contexts.storages}"],
          "@type" : "StorageDeprecated",
          "_storageId" : "$id",
          "_project" : "myorg/myproject",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user",
          "_types": [
            "https://bluebrain.github.io/nexus/vocabulary/Storage",
            "https://bluebrain.github.io/nexus/vocabulary/DiskStorage"
          ],
          "_constrainedBy": "https://bluebrain.github.io/nexus/schemas/storages.json"
        }"""
    }
  }
}
