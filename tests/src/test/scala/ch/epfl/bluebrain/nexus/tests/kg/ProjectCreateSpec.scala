package ch.epfl.bluebrain.nexus.tests.kg

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.listings.Bob
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.resources.SimpleResource
import io.circe.Json

class ProjectCreateSpec extends BaseIntegrationSpec {

  private val logger = Logger[this.type]

  private val org = genId()

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      _ <- adminDsl.createOrganization(org, org, Bob)
    } yield ()

    setup.accepted
  }

  "Create a list of projects" should {
    "work" in {
      (1 to 10_000).toList.traverse { i =>
        val proj = s"proj$i"
        for {
          _ <- logger.error(s"Creating project $proj")
          _ <- adminDsl.createProjectWithName(org, proj, s"projectName$i", Bob)
          _ <- (1 to 10).toList.parTraverse { ir =>
            SimpleResource.sourcePayload(s"http://localhost/$ir", ir).flatMap { payload =>
              deltaClient.post[Json](s"/resources/$org/$proj/_/", payload, Bob) { expectCreated }
            }
          }
        } yield succeed
      }
    }
  }
}
