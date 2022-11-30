package ch.epfl.bluebrain.nexus.migration

import akka.actor.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, ToMigrateEvent}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.migration.Migration.MigrationProgress
import ch.epfl.bluebrain.nexus.migration.config.ReplayConfig
import ch.epfl.bluebrain.nexus.migration.replay.ReplayEvents
import com.typesafe.config.ConfigFactory
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.{Chunk, Stream}
import io.circe.optics.JsonPath.root
import monix.bio.Task
import pureconfig.ConfigSource

import java.time.Instant
import java.util.UUID

final class Migration private (replay: ReplayEvents, blacklisted: Set[String], logs: Set[MigrationLog], batch: BatchConfig, xas: Transactors) {

  private val logMap =  logs.map { log => log.entityType -> log }.toMap

  private def saveProgress(progress: MigrationProgress) =
    sql"""INSERT INTO migration_offset (akka_offset, processed, discarded, failed, instant)
         |VALUES ('migration',${progress.offset.toString}, ${progress.processed}, ${progress.discarded}, ${progress.failed}, ${progress.instant})
         |ON CONFLICT (name)
         |DO UPDATE set
         |  akka_offset = EXCLUDED.akka_offset,
         |  processed = EXCLUDED.processed + migration_offset.processed,
         |  discarded = EXCLUDED.discarded + migration_offset.discarded,
         |  failed = EXCLUDED.failed + migration_offset.failed,
         |  instant = EXCLUDED.instant;
         |""".stripMargin.update.run

  private def saveIgnored(events: Chunk[ToMigrateEvent]) = {
    val sql = """INSERT INTO ignored_events (type, persistence_id, sequence_nr, payload, instant, akka_offset)
         |VALUES (?,?,?,?,?,?)
         |""".stripMargin
    Update[ToMigrateEvent](sql).updateMany(events)
  }

  private def fetchProgress =
    sql"""SELECT akka_offset, processed, discarded, failed, instant FROM migration_offset where name = 'migration'"""
      .query[MigrationProgress].option.transact(xas.read)

  def start: Stream[Task, Elem[Unit]]  =
    Stream.eval(fetchProgress).flatMap { progress =>
      replay.run(progress.map(_.offset)).groupWithin(batch.maxElements, batch.maxInterval).evalTapChunk { chunk =>
        chunk.last.traverse { last =>
          val (toIgnore, toProcess) = chunk.partitionEither { e => Either.cond(!Migration.toIgnore(e, blacklisted), e, e) }
          val migrated = toProcess.traverse { event => logMap(event.entityType)(event) }
          val ignored = saveIgnored(toIgnore).transact(xas.write)
          val updateProgress = saveProgress(MigrationProgress(last.offset, last.instant, toProcess.size.toLong, toIgnore.size.toLong, 0L)).transact(xas.write)
          (migrated >> ignored >> updateProgress)
        }
      }
    }.flatMap(Stream.chunk).map { e =>
      // Forging a bogus elem to run migration in the supervisor
      SuccessElem(e.entityType, iri"https://bbp.epfl.ch/migration", None, e.instant, Offset.start, (), e.sequenceNr.toInt)
    }
}

object Migration {

  final case class MigrationProgress(offset: UUID, instant: Instant, processed: Long, discarded: Long, failed: Long)

  object MigrationProgress {
    implicit val projectionProgressRowRead: Read[MigrationProgress] = {
      Read[(String,  Long, Long, Long, Instant)].map {
        case (offset, processed, discarded, failed, instant) =>
          MigrationProgress(
            UUID.fromString(offset),
            instant,
            processed,
            discarded,
            failed
          )
      }
    }
  }

  private def toIgnore(toMigrateEvent: ToMigrateEvent, projects: Set[String]) = {
    val payload = toMigrateEvent.payload
    root.project.string.all(projects.contains)(payload) ||
      (toMigrateEvent.entityType == Acls.entityType && root.address.string.all { address => projects.contains(address.substring(1)) }(payload))
  }

  def apply(logs: Set[MigrationLog], xas: Transactors, supervisor: Supervisor, system: ActorSystem): Task[Migration] = {
    val config = ConfigFactory.load("migration.conf")
    val batch  = ConfigSource.fromConfig(config).at("migration.batch").loadOrThrow[BatchConfig]
    val replay = ReplayConfig.from(config)
    val cassandraSession = CassandraSessionRegistry.get(system).sessionFor(CassandraSessionSettings())
    val replayEvents = ReplayEvents(cassandraSession, replay)
    val migration = new Migration(replayEvents, Set.empty, logs, batch, xas)
    val metadata = ProjectionMetadata("migration", "migrate-from-cassandra", None, None)
    supervisor.run(CompiledProjection.fromStream(metadata, ExecutionStrategy.TransientSingleNode, _ => migration.start)).as(migration)
  }

}