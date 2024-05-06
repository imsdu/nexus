package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.Uri
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.{HeadObject, UploadMetadata}
import fs2.Stream
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model._

import java.net.URI

trait S3StorageClient {
  def listObjectsV2(bucket: String): IO[ListObjectsV2Response]

  def listObjectsV2(bucket: String, prefix: String): IO[ListObjectsV2Response]

  def readFile(bucket: String, fileKey: String): Stream[IO, Byte]

  def headObject(bucket: String, key: String): IO[HeadObject]

  def copyObject(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      checksumAlgorithm: ChecksumAlgorithm
  ): IO[CopyObjectResponse]

  def copyObjectMultiPart(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String
  ): IO[CompleteMultipartUploadResponse]

  def uploadFile(
      fileData: Stream[IO, Byte],
      bucket: String,
      key: String,
      algorithm: DigestAlgorithm
  ): IO[UploadMetadata]

  def objectExists(bucket: String, key: String): IO[Boolean]
  def bucketExists(bucket: String): IO[Boolean]

  def baseEndpoint: Uri

  def prefix: Uri
}

object S3StorageClient {

  case class UploadMetadata(checksum: String, fileSize: Long, location: Uri)
  case class HeadObject(
      fileSize: Long,
      contentType: Option[String],
      sha256Checksum: Option[String],
      sha1Checksum: Option[String]
  )

  def resource(s3Config: Option[S3StorageConfig]): Resource[IO, S3StorageClient] = s3Config match {
    case Some(cfg) =>
      val creds =
        if (cfg.useDefaultCredentialProvider) DefaultCredentialsProvider.create()
        else {
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(cfg.defaultAccessKey.value, cfg.defaultSecretKey.value)
          )
        }
      resource(URI.create(cfg.defaultEndpoint.toString()), cfg.prefixUri, creds)

    case None => Resource.pure(S3StorageClientDisabled)
  }

  def resource(endpoint: URI, prefix: Uri, credentialProvider: AwsCredentialsProvider): Resource[IO, S3StorageClient] =
    Interpreter[IO]
      .S3AsyncClientOpResource(
        S3AsyncClient
          .builder()
          .credentialsProvider(credentialProvider)
          .endpointOverride(endpoint)
          .forcePathStyle(true)
          .region(Region.US_EAST_1)
      )
      .map(new S3StorageClientImpl(_, endpoint.toString, prefix.toString))

  def unsafe(client: S3AsyncClientOp[IO], defaultEndpoint: Uri, prefix: Uri): S3StorageClient =
    new S3StorageClientImpl(client, defaultEndpoint.toString, prefix)

  def disabled: S3StorageClient = S3StorageClientDisabled
}
