package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.{Chain, NonEmptyChain}
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.SourceIdPipeChainId
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Naturals.NaturalsConfig
import ch.epfl.bluebrain.nexus.testkit.{CollectionAssertions, EitherAssertions, MonixBioSuite}
import monix.bio.Task

import scala.concurrent.duration._

class ProjectionSuite extends MonixBioSuite with ProjectionFixture with EitherAssertions with CollectionAssertions {

  projections.test("Fail to compile SourceChain when source.Out does not match pipe.In") { ctx =>
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain(ctx.logPipe)
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("Fail to compile SourceChain when pipe.Out does not match pipe.In") { ctx =>
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain(ctx.evensPipe, ctx.logPipe)
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("Fail to compile PipeChain when pipe.Out does not match pipe.In") { ctx =>
    PipeChain(
      iri"https://fail",
      NonEmptyChain(ctx.evensPipe, ctx.logPipe)
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("Fail to compile PipeChain when reference is not found in registry") { ctx =>
    PipeChain(
      iri"https://fail",
      NonEmptyChain((PipeRef.unsafe("unknown"), ctx.emptyConfig))
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("Fail to compile SourceChain when reference is not found in registry") { ctx =>
    SourceChain(
      SourceRef.unsafe("unknown"),
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain.empty
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("Fail to compile SourceChain when configuration cannot be decoded") { ctx =>
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      ExpandedJsonLd.empty,
      Chain.empty
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("Fail to compile PipeChain when configuration cannot be decoded") { ctx =>
    PipeChain(
      iri"https://fail",
      NonEmptyChain(TimesN.reference -> ctx.emptyConfig, ctx.intToStringPipe, ctx.logPipe)
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("Fail to compile PipeChain when the terminal type is not Unit") { ctx =>
    PipeChain(
      iri"https://fail",
      NonEmptyChain(ctx.intToStringPipe)
    ).compile(ctx.registry).assertLeft()
  }

  projections.test("All elements emitted by the sources should pass through the defined pipes") { ctx =>
    // tests that multiple sources are merged correctly
    // tests that elements are correctly broadcast across multiple pipes
    // tests that offsets are passed to the sources
    // tests that projections stop when requested

    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://evens",
        NaturalsConfig(10, 2.second).toJsonLd,
        Chain(ctx.evensPipe, ctx.timesNPipe(2))
      ),
      SourceChain(
        Naturals.reference,
        iri"https://odds",
        NaturalsConfig(7, 2.second).toJsonLd,
        Chain(ctx.oddsPipe)
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.timesNPipe(2), ctx.intToStringPipe, ctx.logPipe)
      ),
      PipeChain(
        iri"https://log2",
        NonEmptyChain(ctx.intToStringPipe, ctx.failNPipe(2), ctx.logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(ctx.registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://evens", iri"https://log"), Offset.at(1L))

    // evens chain should emit before stop: 2*2, 4*2, 6*2, 8*2
    // odds chain should emit before stop: 1, 3, 5
    // log chain should see: 2*2*2, 4*2*2, 6*2*2, 8*2*2, 1*2, 3*2, 5*2
    // log2 chain should see 4 elements but nondeterministic depending on how the source elements are emitted
    // total elements in the queue: 11
    val expectedOffset = ProjectionOffset(
      Map(
        SourceIdPipeChainId(iri"https://evens", iri"https://log")  -> Offset.at(9L),
        SourceIdPipeChainId(iri"https://evens", iri"https://log2") -> Offset.at(9L),
        SourceIdPipeChainId(iri"https://odds", iri"https://log")   -> Offset.at(6L),
        SourceIdPipeChainId(iri"https://odds", iri"https://log2")  -> Offset.at(6L)
      )
    )

    for {
      projection <- compiled.start(offset)
      elements   <- ctx.waitForNElements(11, 500.millis)
      empty      <- ctx.waitForNElements(1, 50.millis)
      _           = assertEquals(empty.size, 0, "No other elements should be found after the first 11")
      _          <- projection.isRunning.assert(true)
      _          <- projection.stop()
      _          <- projection.isRunning.assert(false)
      values      = elements.map(_.value)
      _           = assertEquals(values.size, 11, "Exactly 11 elements should be observed on the queue")
      _           = values.assertContainsAllOf(Set(8, 16, 24, 32, 2, 6, 10).map(_.toString))
      elemsOfLog2 = elements.collect {
                      case e @ SuccessElem(SourceIdPipeChainId(_, pipeChain), _, _, _, _, _, _)
                          if pipeChain == iri"https://log2" =>
                        e
                    }
      _           = assertEquals(elemsOfLog2.size, 4, "Exactly 4 elements should pass through log2")
      _          <- projection.offset.assert(expectedOffset)
    } yield ()
  }

  projections.test("Persist the current offset at regular intervals") { ctx =>
    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 50.millis).toJsonLd,
        Chain()
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(ctx.registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(2L))

    for {
      persistCallCountRef <- Ref.of[Task, (Int, ProjectionOffset)]((0, ProjectionOffset.empty))
      persistFn            = (po: ProjectionOffset) => persistCallCountRef.update { case (i, _) => (i + 1, po) }
      readFn               = () => persistCallCountRef.get.map { case (_, po) => po }
      projection          <- compiled.persistOffset(persistFn, readFn, 5.millis).start(offset)
      _                   <- ctx.waitForNElements(10, 50.millis)
      _                   <- projection.stop()
      value               <- persistCallCountRef.get
      (count, observed)    = value
      _                    = assert(count > 1, "The persist fn should have been called at least twice.")
      _                    = assertNotEquals(
                               observed,
                               offset,
                               "The offsets observed by the persist fn should be different than the initial."
                             )
    } yield ()
  }

  projections.test("Stop the projection if the last written offset differs from the current read offset") { ctx =>
    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 20.millis).toJsonLd,
        Chain()
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(ctx.registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(2L))

    for {
      persistRef <- Ref.of[Task, ProjectionOffset](ProjectionOffset.empty)
      persistFn   = (po: ProjectionOffset) => persistRef.set(po)
      readFn      = () => persistRef.get
      projection <- compiled.persistOffset(persistFn, readFn, 5.millis).start(offset)
      _          <- ctx.waitForNElements(1, 50.millis)
      _          <- Task.sleep(5.millis)
      observed   <- persistRef.get
      _           = assertNotEquals(
                      observed,
                      offset,
                      "The offsets observed by the persist fn should be different than the initial."
                    )
      _          <- persistRef.set(ProjectionOffset.empty)
      _          <- Task.sleep(100.millis)
      _          <- projection.isRunning.assert(false, "The projection should have stopped")
      status     <- projection.executionStatus
      _           = assertEquals(status.isStopped, true, status)
      elems      <- ctx.currentElements
      _           = assert(elems.size < 9, "Projection should have stopped before the end")
    } yield ()
  }

  projections.test("Passivate a projection after becoming idle") { ctx =>
    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 2.second).toJsonLd,
        Chain()
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(ctx.registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(9L))
    for {
      projection <- compiled.passivate(100.millis, 5.millis).start()
      _          <- ctx.waitForNElements(9, 50.millis)
      _          <- projection.executionStatus.assert(ExecutionStatus.Running(offset))
      _          <- Task.sleep(500.millis)
      _          <- projection.isRunning.assert(false)
      _          <- projection.executionStatus.assert(ExecutionStatus.Passivated(offset))
    } yield ()
  }

  projections.test("Projections finishing naturally should yield status Completed") { ctx =>
    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 0.millis).toJsonLd,
        Chain()
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(ctx.intToStringPipe, ctx.logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(ctx.registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(10L))
    for {
      projection <- compiled.start()
      _          <- ctx.waitForNElements(10, 50.millis)
      _          <- Task.sleep(50.millis)
      _          <- projection.isRunning.assert(false)
      _          <- projection.executionStatus.assert(ExecutionStatus.Completed(offset))
    } yield ()
  }
}
