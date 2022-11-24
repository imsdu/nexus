package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphIndexingActionSuite.{emptyAcc, IdAcc}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.CouldNotFindPipeErr
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{NoopSink, PipeChain, PipeRef}
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration._

class BlazegraphIndexingActionSuite extends BioSuite with Fixtures {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  private val instant = Instant.EPOCH

  private val project = ProjectRef.unsafe("org", "proj")
  private val id1     = nxv + "view1"
  private val view1   = ActiveViewDef(
    ViewRef(project, id1),
    projection = id1.toString,
    None,
    None,
    namespace = "view1"
  )

  private val id2   = nxv + "view2"
  private val view2 = ActiveViewDef(
    ViewRef(project, id2),
    projection = id2.toString,
    Some(UserTag.unsafe("tag")),
    None,
    namespace = "view2"
  )

  private val id3         = nxv + "view3"
  private val unknownPipe = PipeRef.unsafe("xxx")
  private val view3       = ActiveViewDef(
    ViewRef(project, id3),
    projection = id3.toString,
    None,
    Some(PipeChain(PipeRef.unsafe("xxx") -> ExpandedJsonLd.empty)),
    namespace = "view3"
  )

  private val id4   = nxv + "view4"
  private val view4 = DeprecatedViewDef(
    ViewRef(project, id4)
  )

  private val id5 = nxv + "view5"

  private def viewStream: ElemStream[IndexingViewDef] =
    Stream(
      SuccessElem(
        tpe = BlazegraphViews.entityType,
        id = view1.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(1L),
        value = view1,
        revision = 1
      ),
      SuccessElem(
        tpe = BlazegraphViews.entityType,
        id = view2.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(2L),
        value = view2,
        revision = 1
      ),
      SuccessElem(
        tpe = BlazegraphViews.entityType,
        id = view3.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(3L),
        value = view3,
        revision = 1
      ),
      SuccessElem(
        tpe = BlazegraphViews.entityType,
        id = view4.ref.viewId,
        project = Some(project),
        instant = Instant.EPOCH,
        offset = Offset.at(4L),
        value = view4,
        revision = 1
      ),
      DroppedElem(
        tpe = BlazegraphViews.entityType,
        id = id5,
        project = Some(project),
        Instant.EPOCH,
        Offset.at(5L),
        revision = 1
      )
    )

  private val indexingAction = new BlazegraphIndexingAction(
    _ => viewStream,
    (_: PipeChain) => Left(CouldNotFindPipeErr(unknownPipe)),
    (a: ActiveViewDef) =>
      a.ref.viewId match {
        case `id1` => new NoopSink[NTriples]
        case `id3` => new NoopSink[NTriples]
        case id    => throw new IllegalArgumentException(s"$id should not intent to create a sink")
      },
    patienceConfig.timeout
  )

  private val base = iri"http://localhost"
  private val pr   = PullRequestActive(
    id = Label.unsafe("id1"),
    project = project,
    rev = 1,
    createdAt = instant,
    createdBy = Anonymous,
    updatedAt = instant,
    updatedBy = Anonymous
  )

  private val elem = SuccessElem(
    tpe = PullRequest.entityType,
    id = pr.id.value,
    project = Some(project),
    instant = pr.updatedAt,
    offset = Offset.at(1L),
    value = PullRequestState.toGraphResource(pr, base),
    revision = 1
  )

  test("Collect only the adequate views") {
    val expected = IdAcc(
      Set(id1.toString),
      Set(id2.toString, id4.toString, id5.toString),
      Set(id3.toString)
    )

    indexingAction
      .projections(project, elem)
      .fold(emptyAcc) {
        case (acc, s: SuccessElem[_]) => acc.success(s.id)
        case (acc, d: DroppedElem)    => acc.drop(d.id)
        case (acc, f: FailedElem)     => acc.failed(f.id)
      }
      .compile
      .lastOrError
      .assert(expected)
  }

  test("A valid elem should be indexed") {
    indexingAction.apply(project, elem).assert(List.empty)
  }

  test("A failed elem should be returned") {
    val failed = FailedElem(
      tpe = PullRequest.entityType,
      id = pr.id.value,
      project = Some(project),
      instant = pr.updatedAt,
      offset = Offset.at(1L),
      new IllegalStateException("Boom"),
      revision = 1
    )

    indexingAction.apply(project, failed).assert(List(failed))
  }

}

object BlazegraphIndexingActionSuite {

  final private case class IdAcc(successes: Set[String], dropped: Set[String], failures: Set[String]) {
    def success(id: String): IdAcc = this.copy(successes = successes + id)
    def drop(id: String): IdAcc    = this.copy(dropped = dropped + id)
    def failed(id: String): IdAcc  = this.copy(failures = failures + id)
  }

  private val emptyAcc = IdAcc(Set.empty[String], Set.empty[String], Set.empty[String])

}