package ch.epfl.bluebrain.nexus.sourcingnew.eventsource

import akka.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final case class EventSourceConfig(askTimeout: Timeout,
                                   evaluationMaxDuration: FiniteDuration,
                                   evaluationExecutionContext: ExecutionContext,
                                   stashSize: Int)
