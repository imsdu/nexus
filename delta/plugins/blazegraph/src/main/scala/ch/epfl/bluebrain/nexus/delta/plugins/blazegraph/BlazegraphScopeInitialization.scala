package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.{MigrationState, ScopeInitialization}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of the default SparqlView as part of the project initialization. It performs a noop if
  * executed during a migration.
  *
  * @param views          the BlazegraphViews module
  * @param serviceAccount the subject that will be recorded when performing the initialization
  */
class BlazegraphScopeInitialization(views: BlazegraphViews, serviceAccount: ServiceAccount)
    extends ScopeInitialization {

  private val logger: Logger          = Logger[BlazegraphScopeInitialization]
  implicit private val caller: Caller = serviceAccount.caller

  private val defaultValue: IndexingBlazegraphViewValue = IndexingBlazegraphViewValue(
    resourceSchemas = Set.empty,
    resourceTypes = Set.empty,
    resourceTag = None,
    includeMetadata = true,
    includeDeprecated = true,
    permission = defaultPermission
  )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[ScopeInitializationFailed, Unit] =
    if (MigrationState.isRunning) UIO.unit
    else
      views
        .create(defaultViewId, project.ref, defaultValue)
        .void
        .onErrorHandleWith {
          case _: BlazegraphViewRejection.ViewAlreadyExists => UIO.unit // nothing to do, view already exits
          case rej                                          =>
            val str =
              s"Failed to create the default SparqlView for project '${project.ref}' due to '${rej.reason}'."
            UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
        }
        .named("createDefaultSparqlView", BlazegraphViews.moduleType)

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit

}
