package ch.epfl.bluebrain.nexus.delta.plugins.statistics.config

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.config.StatisticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import com.typesafe.config.Config
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.util.Try

/**
  * Configuration for the ElasticSearchView plugin.
  *
  * @param base
  *   the base uri to the Elasticsearch HTTP endpoint
  * @param keyValueStore
  *   configuration of the underlying key/value store
  * @param indexing
  *   configuration of the external indexing process
  * @param idleTimeout
  *   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  * @param termAggregations
  *   the term aggregations query configuration
  */
final case class StatisticsConfig(
    base: Uri,
    keyValueStore: KeyValueStoreConfig,
    indexing: ExternalIndexingConfig,
    idleTimeout: Duration,
    termAggregations: TermAggregationsConfig
)

object StatisticsConfig {

  /**
    * Configuration for term aggregation queries
    * @param size
    *   the global number of terms returned by the aggregation. The term aggregation is requested to each shard and once
    *   all the shards responded, the coordinating node will then reduce them to a final result which will be based on
    *   this size parameter The higher the requested size is, the more accurate the results will be, but also, the more
    *   expensive it will be to compute the final results
    * @param shardSize
    *   the number of terms the coordinating node returns from each shard. This value must be higher than ''size''
    */
  final case class TermAggregationsConfig(size: Int, shardSize: Int)

  /**
    * Converts a [[Config]] into an [[StatisticsConfig]]
    */
  def load(config: Config): StatisticsConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.statistics")
      .loadOrThrow[StatisticsConfig]

  @nowarn("cat=unused")
  implicit private val uriConfigReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str))
      .filter(_.isAbsolute)
      .toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

  @nowarn("cat=unused")
  implicit final private val termAggregationsConfigReader: ConfigReader[TermAggregationsConfig] =
    deriveReader[TermAggregationsConfig]

  implicit final val statisticsConfigReader: ConfigReader[StatisticsConfig] =
    deriveReader[StatisticsConfig].emap { c =>
      (validateIdleTimeout(c), validateAggregations(c.termAggregations)).mapN((_, _) => c)
    }

  private def validateIdleTimeout(cfg: StatisticsConfig) =
    Either.cond(
      cfg.idleTimeout.gteq(10.minutes),
      (),
      failure("'idle-timeout' must be greater than 10 minutes")
    )

  private def validateAggregations(cfg: TermAggregationsConfig) =
    Either.cond(
      cfg.shardSize > cfg.size,
      (),
      failure("'shard-size' must be greater than 'size' (recommended shard-size ~ 1.5 size)")
    )

  private def failure(reason: String): FailureReason =
    new FailureReason {
      override def description: String = reason
    }
}