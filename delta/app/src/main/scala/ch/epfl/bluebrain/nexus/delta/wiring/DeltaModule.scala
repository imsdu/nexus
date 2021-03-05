package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.BootstrapSetup
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import akka.stream.{Materializer, SystemMaterializer}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventExchange, EventExchangeCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectCountsCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.service.utils.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import io.circe.{Decoder, Encoder}
import izumi.distage.model.definition.ModuleDef
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}

/**
  * Complete service wiring definitions.
  *
  * @param appCfg      the application configuration
  * @param config      the raw merged and resolved configuration
  */
class DeltaModule(appCfg: AppConfig, config: Config)(implicit classLoader: ClassLoader) extends ModuleDef {

  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseFlavour].from { appCfg.database.flavour }
  make[BaseUri].from { appCfg.http.baseUri }
  make[ServiceAccount].from { appCfg.serviceAccount.value }
  make[List[PluginDescription]].from { (pluginsDef: List[PluginDef]) => pluginsDef.map(_.info) }

  make[RemoteContextResolution].from { (pluginsDef: List[PluginDef]) =>
    RemoteContextResolution
      .fixedIOResource(
        contexts.acls          -> ioJsonContentOf("contexts/acls.json").memoizeOnSuccess,
        contexts.error         -> ioJsonContentOf("contexts/error.json").memoizeOnSuccess,
        contexts.identities    -> ioJsonContentOf("contexts/identities.json").memoizeOnSuccess,
        contexts.offset        -> ioJsonContentOf("contexts/offset.json").memoizeOnSuccess,
        contexts.organizations -> ioJsonContentOf("contexts/organizations.json").memoizeOnSuccess,
        contexts.permissions   -> ioJsonContentOf("contexts/permissions.json").memoizeOnSuccess,
        contexts.projects      -> ioJsonContentOf("contexts/projects.json").memoizeOnSuccess,
        contexts.realms        -> ioJsonContentOf("contexts/realms.json").memoizeOnSuccess,
        contexts.resolvers     -> ioJsonContentOf("contexts/resolvers.json").memoizeOnSuccess,
        contexts.metadata      -> ioJsonContentOf("contexts/metadata.json").memoizeOnSuccess,
        contexts.search        -> ioJsonContentOf("contexts/search.json").memoizeOnSuccess,
        contexts.shacl         -> ioJsonContentOf("contexts/shacl.json").memoizeOnSuccess,
        contexts.statistics    -> ioJsonContentOf("contexts/statistics.json").memoizeOnSuccess,
        contexts.tags          -> ioJsonContentOf("contexts/tags.json").memoizeOnSuccess,
        contexts.version       -> ioJsonContentOf("contexts/version.json").memoizeOnSuccess
      )
      .merge(pluginsDef.map(_.remoteContextResolution): _*)
  }

  many[ApiMappings].addSet { (pluginsDef: List[PluginDef]) => pluginsDef.map(_.apiMappings).toSet }

  make[Clock[UIO]].from(Clock[UIO])
  make[UUIDF].from(UUIDF.random)
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering(
      topKeys = List("@context", "@id", "@type", "reason", "details"),
      bottomKeys =
        List("_rev", "_deprecated", "_createdAt", "_createdBy", "_updatedAt", "_updatedBy", "_constrainedBy", "_self")
    )
  )
  make[ActorSystem[Nothing]].from(
    ActorSystem[Nothing](
      Behaviors.empty,
      appCfg.description.fullName,
      BootstrapSetup().withConfig(config).withClassloader(classLoader)
    )
  )
  make[Materializer].from((as: ActorSystem[Nothing]) => SystemMaterializer(as).materializer)
  make[Logger].from { LoggerFactory.getLogger("delta") }
  make[RejectionHandler].from { (s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering) =>
    RdfRejectionHandler(s, cr, ordering)
  }
  make[ExceptionHandler].from { (s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering) =>
    RdfExceptionHandler(s, cr, ordering)
  }
  make[CorsSettings].from(
    CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
  )

  make[EventLog[Envelope[Event]]].fromEffect { databaseEventLog[Event](_, _) }

  make[EventExchangeCollection].from { (exchanges: Set[EventExchange]) =>
    EventExchangeCollection(exchanges)
  }

  make[Projection[ProjectCountsCollection]].fromEffect { (system: ActorSystem[Nothing], clock: Clock[UIO]) =>
    projection(ProjectCountsCollection.empty, system, clock)
  }

  make[Projection[Unit]].fromEffect { (system: ActorSystem[Nothing], clock: Clock[UIO]) =>
    projection((), system, clock)
  }

  make[ProjectsCounts].fromEffect {
    (
        projection: Projection[ProjectCountsCollection],
        eventLog: EventLog[Envelope[Event]],
        as: ActorSystem[Nothing],
        sc: Scheduler
    ) =>
      ProjectsCounts(appCfg.projects, projection, eventLog.eventsByTag(Event.eventTag, _))(as, sc)
  }

  many[ScopeInitialization].add { (acls: Acls, serviceAccount: ServiceAccount) =>
    new OwnerPermissionsScopeInitialization(acls, appCfg.permissions.ownerPermissions, serviceAccount)
  }

  include(PermissionsModule)
  include(AclsModule)
  include(RealmsModule)
  include(OrganizationsModule)
  include(ProjectsModule)
  include(ResolversModule)
  include(SchemasModule)
  include(ResourcesModule)
  include(IdentitiesModule)
  include(VersionModule)

  private def projection[A: Decoder: Encoder](
      empty: => A,
      system: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): Task[Projection[A]] = {
    implicit val as: ActorSystem[Nothing] = system
    implicit val c: Clock[UIO]            = clock
    appCfg.database.flavour match {
      case Postgres  => Projection.postgres(appCfg.database.postgres, empty)
      case Cassandra => Projection.cassandra(appCfg.database.cassandra, empty)
    }
  }
}

object DeltaModule {

  /**
    * Complete service wiring definitions.
    *
    * @param appCfg      the application configuration
    * @param config      the raw merged and resolved configuration
    * @param classLoader the aggregated class loader
    */
  final def apply(
      appCfg: AppConfig,
      config: Config,
      classLoader: ClassLoader
  ): DeltaModule =
    new DeltaModule(appCfg, config)(classLoader)
}
