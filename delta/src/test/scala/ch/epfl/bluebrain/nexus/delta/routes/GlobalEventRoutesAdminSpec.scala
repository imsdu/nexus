package ch.epfl.bluebrain.nexus.delta.routes

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.config.Permissions.{events, orgs, projects}
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent._
import ch.epfl.bluebrain.nexus.delta.config.Settings
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission, ResourceF => IamResourceF}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import monix.eval.Task
import org.mockito.IdiomaticMockito
import org.mockito.matchers.MacroBasedMatchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}

import scala.concurrent.duration._

//noinspection TypeAnnotation
class GlobalEventRoutesAdminSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfter
    with MacroBasedMatchers
    with Resources
    with ScalaFutures
    with OptionValues
    with EitherValues
    with Inspectors
    with IdiomaticMockito {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 100.milliseconds)

  override def testConfig: Config = ConfigFactory.load("test.conf")

  private val config        = Settings(system).appConfig
  implicit private val http = config.http
  implicit private val pc   = config.persistence
  private val prefix        = config.http.prefix
  private val aclsApi       = mock[Acls[Task]]
  private val realmsApi     = mock[Realms[Task]]

  val orgRead   = Permission.unsafe("organizations/read")
  val projRead  = Permission.unsafe("projects/read")
  val eventRead = Permission.unsafe("events/read")
  aclsApi.hasPermission(Path./, orgRead)(Caller.anonymous) shouldReturn Task.pure(true)
  aclsApi.hasPermission(Path./, projRead)(Caller.anonymous) shouldReturn Task.pure(true)
  aclsApi.hasPermission(Path./, eventRead)(Caller.anonymous) shouldReturn Task.pure(true)

  before {
    aclsApi.list(Path./, ancestors = true, self = true)(Caller.anonymous) shouldReturn Task.pure(
      AccessControlLists(
        Path./ -> IamResourceF(
          url"http://nexus.example.com/",
          1L,
          Set.empty,
          Instant.now(),
          subject,
          Instant.now(),
          subject,
          AccessControlList(Anonymous -> Set(events.read, orgs.read, projects.read))
        )
      )
    )
  }

  val instant = Instant.EPOCH
  val subject = User("uuid", "myrealm")

  val orgUuid            = UUID.fromString("d8cf3015-1bce-4dda-ba80-80cd4b5281e5")
  val orgLabel           = "thelabel"
  val orgDescription     = Option("the description")
  val projectUuid        = UUID.fromString("94463ac0-3e9b-4261-80f5-e4253956eee2")
  val projectLabel       = "theprojectlabel"
  val projectDescription = "the project description"
  val projectBase        = url"http://localhost:8080/base/"
  val projectVocab       = url"http://localhost:8080/vocab/"
  val apiMappings        = Map(
    "nxv" -> url"https://bluebrain.github.io/nexus/vocabulary/",
    "rdf" -> url"http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  )

  val orgEvents = List(
    OrganizationCreated(
      orgUuid,
      orgLabel,
      orgDescription,
      instant,
      subject
    ),
    OrganizationUpdated(
      orgUuid,
      2L,
      orgLabel,
      orgDescription,
      instant,
      subject
    ),
    OrganizationDeprecated(
      orgUuid,
      2L,
      instant,
      subject
    )
  )

  val projectEvents = List(
    ProjectCreated(
      projectUuid,
      projectLabel,
      orgUuid,
      orgLabel,
      Some(projectDescription),
      apiMappings,
      projectBase,
      projectVocab,
      instant,
      subject
    ),
    ProjectUpdated(
      projectUuid,
      projectLabel,
      Some(projectDescription),
      apiMappings,
      projectBase,
      projectVocab,
      2L,
      instant,
      subject
    ),
    ProjectDeprecated(
      projectUuid,
      2L,
      instant,
      subject
    )
  )

  val orgEventsJsons = Vector(
    jsonContentOf("/events/org-created.json"),
    jsonContentOf("/events/org-updated.json"),
    jsonContentOf("/events/org-deprecated.json")
  )

  val projectEventsJsons = Vector(
    jsonContentOf("/events/project-created.json"),
    jsonContentOf("/events/project-updated.json"),
    jsonContentOf("/events/project-deprecated.json")
  )

  def eventStreamFor(jsons: Vector[Json], drop: Int = 0): String =
    jsons.zipWithIndex
      .drop(drop)
      .map {
        case (json, idx) =>
          val data  = json.noSpaces
          val event = json.hcursor.get[String]("@type").rightValue
          val id    = idx
          s"""data:$data
             |event:$event
             |id:$id""".stripMargin
      }
      .mkString("", "\n\n", "\n\n")

  "The EventRoutes" should {
    "return the organization events in the right order" in {
      val routes = new TestableEventRoutes(orgEvents, aclsApi, realmsApi).routes
      forAll(List(s"/$prefix/orgs/events", s"/$prefix/orgs/events/")) { path =>
        Get(path) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(orgEventsJsons)
        }
      }
    }
    "return the project events in the right order" in {
      val routes = new TestableEventRoutes(projectEvents, aclsApi, realmsApi).routes
      forAll(List(s"/$prefix/projects/events", s"/$prefix/projects/events/")) { path =>
        Get(path) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(projectEventsJsons)
        }
      }
    }

    "return Forbidden when requesting the log with no permissions" in {
      val aclsApi2  = mock[Acls[Task]]
      aclsApi2.hasPermission(Path./, eventRead)(Caller.anonymous) shouldReturn Task.pure(false)
      aclsApi2.hasPermission(Path./, orgRead)(Caller.anonymous) shouldReturn Task.pure(false)
      aclsApi2.hasPermission(Path./, projRead)(Caller.anonymous) shouldReturn Task.pure(false)
      aclsApi2.list(Path./, ancestors = true, self = true)(Caller.anonymous) shouldReturn Task(AccessControlLists.empty)
      val routes    = Routes.wrap(new TestableEventRoutes(orgEvents ++ projectEvents, aclsApi2, realmsApi).routes)
      val endpoints = List(
        s"/$prefix/orgs/events",
        s"/$prefix/orgs/events/",
        s"/$prefix/projects/events",
        s"/$prefix/projects/events/"
      )
      forAll(endpoints) { path =>
        Get(path) ~> routes ~> check {
          status shouldEqual StatusCodes.Forbidden
        }
      }
    }
  }
}
