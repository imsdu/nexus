package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model._
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

class FormDataExtractorSpec
    extends TestKit(ActorSystem("FormDataExtractorSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers {

  "A Form Data HttpEntity" should {

    val content = "file content"

    val customMediaType   = MediaType.parse("application/custom").rightValue
    val customContentType = ContentType(customMediaType, () => HttpCharsets.`UTF-8`)
    val mediaTypeDetector = MediaTypeDetectorConfig(Map("custom" -> customMediaType))
    val extractor         = FormDataExtractor(new MediaTypeDetector(mediaTypeDetector))

    def createEntity(
        bodyPart: String,
        contentType: ContentType,
        filename: Option[String],
        keywords: Map[String, Json] = Map.empty,
        description: Option[String] = None,
        name: Option[String] = None
    ) =
      Multipart
        .FormData(
          Multipart.FormData
            .BodyPart(
              bodyPart,
              HttpEntity(contentType, content.getBytes),
              dispositionParameters(filename, keywords, description, name)
            )
        )
        .toEntity()

    def dispositionParameters(
        filename: Option[String],
        keywords: Map[String, Json],
        description: Option[String],
        name: Option[String]
    ): Map[String, String] = {

      val metadata = JsonObject(
        "name"        -> name.asJson,
        "description" -> description.asJson,
        "keywords"    -> JsonObject.fromMap(keywords).toJson
      ).toJson

      Map.from(
        filename.map("filename"                       -> _) ++
          Option.when(!metadata.isEmpty())("metadata" -> metadata.noSpaces)
      )
    }

    "be extracted with the default content type" in {
      val entity = createEntity("file", NoContentType, Some("filename"))

      val UploadedFileInformation(filename, contentType, contents) =
        extractor(entity, 250).accepted

      filename shouldEqual "filename"
      contentType.value shouldEqual `application/octet-stream`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the custom media type from the detector" in {
      val entity                                                   = createEntity("file", NoContentType, Some("file.custom"))
      val UploadedFileInformation(filename, contentType, contents) = extractor(entity, 2000).accepted

      filename shouldEqual "file.custom"
      contentType.value shouldEqual customContentType
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the akka detection from the extension" in {
      val entity = createEntity("file", NoContentType, Some("file.txt"))

      val UploadedFileInformation(filename, contentType, contents) = extractor(entity, 250).accepted
      filename shouldEqual "file.txt"
      contentType.value shouldEqual `text/plain(UTF-8)`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the default filename when none is provided" in {
      val entity = createEntity("file", NoContentType, None)

      val filename = extractor(entity, 250).accepted.filename
      filename shouldEqual "file"
    }

    "be extracted with the default filename when an empty string is provided" in {
      val entity = createEntity("file", NoContentType, Some(""))

      val filename = extractor(entity, 250).accepted.filename
      filename shouldEqual "file"
    }

    "be extracted with the provided content type header" in {
      val entity                                                   = createEntity("file", `text/plain(UTF-8)`, Some("file.custom"))
      val UploadedFileInformation(filename, contentType, contents) = extractor(entity, 2000).accepted
      filename shouldEqual "file.custom"
      contentType.value shouldEqual `text/plain(UTF-8)`
      consume(contents.dataBytes) shouldEqual content
    }

    "fail to be extracted if no file part exists found" in {
      val entity = createEntity("other", NoContentType, None)
      extractor(entity, 250).rejectedWith[InvalidMultipartFieldName.type]
    }

    "fail to be extracted if payload size is too large" in {
      val entity = createEntity("other", `text/plain(UTF-8)`, None)
      extractor(entity, 10).rejected shouldEqual FileTooLarge(10L)
    }
  }
}
