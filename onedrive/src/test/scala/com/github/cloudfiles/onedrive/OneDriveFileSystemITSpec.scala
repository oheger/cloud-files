/*
 * Copyright 2020-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.cloudfiles.onedrive

import com.github.cloudfiles.core._
import com.github.cloudfiles.core.delegate.ElementPatchSpec
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.cloudfiles.core.http.{MultiHostExtension, ProxyITSpec, ProxySupport, Secret}
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol.WritableFileSystemInfo
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.AbsentPattern
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.when
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.Instant
import java.util.concurrent.TimeoutException
import scala.concurrent.Future
import scala.concurrent.duration._

object OneDriveFileSystemITSpec {
  /** Test OneDrive ID. */
  private val DriveID = "1234567890"

  /** The base path of the server API. */
  private val ServerPath = "/v1.0/me/drives"

  /** The root path within OneDrive. */
  private val RootPath = "/my/data"

  /** A test ID that appears in some test server responses. */
  private val ResolvedID = "some_test_id"

  /** A test file name used by multiple test cases. */
  private val FileName = "test File.txt"

  /** The encoded test file name. */
  private val FileNameEncoded = "test%20File.txt"

  /** File info object for a test file referenced by multiple test cases. */
  private val TestFileInfo = OneDriveJsonProtocol.WritableFileSystemInfo(
    lastModifiedDateTime = Some(Instant.parse("2021-01-30T15:54:20.224Z")),
    lastAccessedDateTime = Some(Instant.parse("2021-01-30T15:54:48.448Z")))

  /** Test URI to upload files. */
  private val UploadUri = "/file-storage/data/temp123456.xyz"

  /** Constant for the Accept header. */
  private val HeaderAccept = "Accept"

  /** Constant for the JSON content type for the accept header. */
  private val ContentJson = "application/json"

  /**
   * Generates the full relative URI that corresponds to the given path.
   * Appends the correct prefix for all requests.
   *
   * @param path the path to be resolved
   * @return the relative URI corresponding to this path
   */
  private def drivePath(path: String): String =
    s"$ServerPath/$DriveID$path"
}

/**
 * Test class for ''OneDriveFileSystem''.
 */
class OneDriveFileSystemITSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers
  with MockitoSugar with WireMockSupport with FileTestHelper {
  override protected val resourceRoot: String = "onedrive"

  import OneDriveFileSystemITSpec._

  /**
   * Creates a test configuration that points to the local WireMock server.
   *
   * @return the test configuration
   */
  private def createConfig(): OneDriveConfig =
    OneDriveConfig(driveID = DriveID, serverUri = serverUri(ServerPath), optRootPath = Some(RootPath))

  /**
   * Executes the given file system operation against the mock server.
   *
   * @param op the operation to execute
   * @tparam A the result type of the operation
   * @return a future with the result of the operation
   */
  private def runOp[A](op: FileSystem.Operation[A]): Future[A] = {
    val httpActor = spawn(MultiHostExtension())
    op.run(httpActor)
  }

  /**
   * Prepares the mock server to expect a request to resolve an element and to
   * return a corresponding response.
   *
   * @param path the expected path of the element
   */
  private def stubResolvePath(path: String): Unit = {
    stubFor(get(urlPathEqualTo(drivePath(path)))
      .withQueryParam("select", equalTo("id"))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .willReturn(aJsonResponse()
        .withBodyFile("resolve_response.json")))
  }

  "OneDriveFileSystem" should "determine the root ID for an undefined root path" in {
    stubResolvePath("/root")
    val fs = new OneDriveFileSystem(createConfig().copy(optRootPath = None))

    runOp(fs.rootID) map { rootID =>
      rootID should be(ResolvedID)
    }
  }

  it should "determine the root ID if a root path is specified" in {
    stubResolvePath(s"/root:$RootPath:")
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.rootID) map { rootID =>
      rootID should be(ResolvedID)
    }
  }

  it should "handle a trailing slash in the server URI" in {
    stubResolvePath(s"/root:$RootPath:")
    val config = createConfig()
    val fs = new OneDriveFileSystem(config.copy(serverUri = config.serverUri + "/"))

    runOp(fs.rootID) map { rootID =>
      rootID should be(ResolvedID)
    }
  }

  it should "handle a missing leading slash in the root path" in {
    stubResolvePath(s"/root:$RootPath:")
    val config = createConfig()
    val fs = new OneDriveFileSystem(config.copy(optRootPath = Some(RootPath.drop(1))))

    runOp(fs.rootID) map { rootID =>
      rootID should be(ResolvedID)
    }
  }

  it should "resolve a path for an undefined root path" in {
    val path = "/the/path/to/resolve"
    stubResolvePath(s"/root:$path:")
    val fs = new OneDriveFileSystem(createConfig().copy(optRootPath = None))

    runOp(fs.resolvePath(path)) map { pathID =>
      pathID should be(ResolvedID)
    }
  }

  it should "resolve a path if a root path is specified" in {
    val path = "/the/path/to/resolve/from/root/foo.txt"
    stubResolvePath(s"/root:$RootPath$path:")
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.resolvePath(path)) map { pathID =>
      pathID should be(ResolvedID)
    }
  }

  it should "resolve a path that does not start with a slash" in {
    val path = "path/no/slash"
    stubResolvePath(s"/root:$RootPath/$path:")
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.resolvePath(path)) map { pathID =>
      pathID should be(ResolvedID)
    }
  }

  it should "resolve a folder by its ID" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_folder_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.resolveFolder(ResolvedID)) map { folder =>
      folder.id should be(ResolvedID)
      folder.name should be("data")
      folder.description should be(None)
      folder.folderData.childCount should be(9)
      folder.item.fileSystemInfo.createdDateTime should be(Instant.parse("2019-11-12T14:32:50.8Z"))
    }
  }

  it should "check whether the ID points to a folder when resolving it" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    recoverToSucceededIf[IllegalArgumentException](runOp(fs.resolveFolder(ResolvedID)))
  }

  it should "resolve a file by its ID" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.resolveFile(ResolvedID)) map { file =>
      file.id should be(ResolvedID)
      file.name should be("test.txt")
      file.size should be(327)
      file.fileData.mimeType should be("application/octet-stream")
      file.fileData.hashes.sha1Hash should be(Some("319D8515AC0683C7EA6AF60A547E142141F11BF5"))
    }
  }

  it should "check whether the ID points to a file when resolving it" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_folder_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    recoverToSucceededIf[IllegalArgumentException](runOp(fs.resolveFile(ResolvedID)))
  }

  it should "return the content of a folder" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID/children")))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("folder_children_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.folderContent(ResolvedID)) map { content =>
      content.folderID should be(ResolvedID)
      content.folders should have size 2
      content.folders("xxxyyyzzz1234567!7193").name should be("subFolder1")
      content.folders("xxxyyyzzz1234567!4891").name should be("subFolder2")
      content.files should have size 2
      content.files("xxxyyyzzz1234567!26990").name should be("data.json")
      content.files("xxxyyyzzz1234567!26988").name should be("info.txt")
    }
  }

  it should "return the content of a folder split onto multiple pages" in {
    val nextUri = drivePath("/next/folder/children")
    val folder1Data = readDataFile(resourceFile("/__files/folder_children_with_next_response.json"))
      .replace("${next.folder}", serverUri(nextUri))
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID/children")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBody(folder1Data)))
    stubFor(get(urlPathEqualTo(nextUri))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("folder_children_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.folderContent(ResolvedID)) map { content =>
      content.folderID should be(ResolvedID)
      content.folders should have size 2
      content.files should have size 4
      content.files("xxxyyyzzz1234567!26990").name should be("data.json")
      content.files("xxxyyyzzz1234567!26124").name should be("file (2).mp3")
    }
  }

  it should "delete a folder" in {
    val deletePath = drivePath(s"/items/$ResolvedID")
    stubFor(delete(urlPathEqualTo(deletePath))
      .willReturn(aResponse().withStatus(StatusCodes.NoContent.intValue)))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.deleteFolder(ResolvedID)) map { _ =>
      verify(deleteRequestedFor(urlPathEqualTo(deletePath)))
      succeed
    }
  }

  it should "discard the entities of requests where the response does not matter" in {
    stubSuccess(WireMockSupport.NoAuthFunc)
    val fs = new OneDriveFileSystem(createConfig())

    // Execute a number of operations. If the entities are not discarded, the
    // HTTP pipeline will block, and we will run into a timeout.
    val futResults = (1 to 16) map { idx =>
      val id = s"id$idx"
      runOp(fs.deleteFolder(id))
    }
    Future.sequence(futResults) map { _ =>
      succeed
    }
  }

  it should "delete a file" in {
    val deletePath = drivePath(s"/items/$ResolvedID")
    stubFor(delete(urlPathEqualTo(deletePath))
      .willReturn(aResponse().withStatus(StatusCodes.NoContent.intValue)))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.deleteFile(ResolvedID)) map { _ =>
      verify(deleteRequestedFor(urlPathEqualTo(deletePath)))
      succeed
    }
  }

  it should "create a new folder" in {
    val ParentId = "someParent"
    val expBody = readDataFile(resourceFile("/createFolder.json"))
    stubFor(post(urlPathEqualTo(drivePath(s"/items/$ParentId/children")))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.Created)
        .withBodyFile("resolve_folder_response.json")))
    val fsInfo =
      OneDriveJsonProtocol.WritableFileSystemInfo(createdDateTime = Some(Instant.parse("2021-01-23T20:07:10.113Z")))
    val folder = OneDriveModel.newFolder("cloud-files", info = Some(fsInfo),
      description = Some("This is the description of the test folder."))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.createFolder(ParentId, folder)) map { folderId =>
      folderId should be(ResolvedID)
    }
  }

  it should "create a new folder from another Folder implementation" in {
    val folder = mock[Model.Folder[String]]
    when(folder.name).thenReturn("cloud-files")
    when(folder.description).thenReturn(None)
    val expBody = readDataFile(resourceFile("/createFolderMinimum.json"))
    stubFor(post(urlPathEqualTo(drivePath(s"/items/$ResolvedID/children")))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.Created)
        .withBodyFile("resolve_folder_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.createFolder(ResolvedID, folder)) map { folderId =>
      folderId should be(ResolvedID)
    }
  }

  it should "update a folder" in {
    val expBody = readDataFile(resourceFile("/createFolderMinimum.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    stubFor(patch(urlPathEqualTo(updatePath))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_folder_response.json")))
    val folder = OneDriveModel.newFolder(id = ResolvedID, name = "cloud-files",
      info = Some(OneDriveJsonProtocol.WritableFileSystemInfo()))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.updateFolder(folder)) map { _ =>
      verify(patchRequestedFor(urlPathEqualTo(updatePath)))
      succeed
    }
  }

  it should "update a file" in {
    val expBody = readDataFile(resourceFile("/createFile.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    stubFor(patch(urlPathEqualTo(updatePath))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val file = OneDriveModel.newFile(id = ResolvedID, name = FileName, info = Some(TestFileInfo), size = 2048)
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.updateFile(file)) map { _ =>
      verify(patchRequestedFor(urlPathEqualTo(updatePath)))
      succeed
    }
  }

  it should "update a file from another File implementation" in {
    val file = mock[Model.File[String]]
    when(file.id).thenReturn(ResolvedID)
    when(file.description).thenReturn(Some("A test file."))
    val expBody = readDataFile(resourceFile("/createFileMinimum.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    stubFor(patch(urlPathEqualTo(updatePath))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.updateFile(file)) map { _ =>
      verify(patchRequestedFor(urlPathEqualTo(updatePath)))
      succeed
    }
  }

  it should "download the content of a file" in {
    val DownloadUri = "/path/to/download/file.dat"
    runWithNewServer { server =>
      stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID/content")))
        .willReturn(aResponse().withStatus(StatusCodes.Found.intValue)
          .withHeader("Location", WireMockSupport.serverUri(server, DownloadUri))))
      server.stubFor(get(urlPathEqualTo(DownloadUri))
        .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
          .withBody(FileTestHelper.TestData)))
      val fs = new OneDriveFileSystem(createConfig())

      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      for {
        source <- runOp(fs.downloadFile(ResolvedID))
        content <- source.dataBytes.runWith(sink)
      } yield content.utf8String should be(FileTestHelper.TestData)
    }
  }

  it should "handle a missing Location header when downloading a file" in {
    val path = drivePath(s"/items/$ResolvedID/content")
    stubFor(get(urlPathEqualTo(path))
      .willReturn(aResponse().withStatus(StatusCodes.Found.intValue)
        .withBody("Missing Location header")))
    val fs = new OneDriveFileSystem(createConfig())

    recoverToExceptionIf[IllegalStateException](runOp(fs.downloadFile(ResolvedID))) map { ex =>
      ex.getMessage should include(path)
    }
  }

  /**
   * Generates the JSON response of a request for an upload session.
   *
   * @param server the server to send the upload chunks to
   * @return the response of an upload session request
   */
  private def uploadSessionResponse(server: WireMockServer): String = {
    val ExpirationTime = Instant.now().plusSeconds(300).toString
    s"""
       |{
       |  "uploadUrl": "${WireMockSupport.serverUri(server, UploadUri)}",
       |  "expirationDateTime": "$ExpirationTime"
       |}
       |""".stripMargin
  }

  /**
   * Checks a file upload operation with different parameters.
   *
   * @param uploadChunkSize the upload chunk size
   * @param groupSize       the group size in the stream
   * @return the ''Future'' with the test assertion
   */
  private def checkUploadNewFile(uploadChunkSize: Int, groupSize: Int): Future[Assertion] = {
    val ParentId = ResolvedID.reverse
    val FileDescription = Some("Upload test file description")
    val Content = FileTestHelper.TestData * 8
    val fileInfo = WritableFileSystemInfo(createdDateTime = Some(Instant.parse("2021-01-31T21:21:10.123Z")),
      lastModifiedDateTime = Some(Instant.parse("2021-01-31T21:21:50.987Z")))
    val file = OneDriveModel.newFile(name = FileName, description = FileDescription, info = Some(fileInfo),
      size = Content.length)
    val SourceUri = drivePath(s"/items/$ParentId:/$FileNameEncoded:/createUploadSession")
    val uploadSessionRequest = readDataFile(resourceFile("/createUploadSession.json"))
    val fileSource = Source(Content.grouped(groupSize).toList).map(ByteString(_))

    runWithNewServer {
      server =>
        stubFor(post(urlPathEqualTo(SourceUri))
          .withHeader("Content-Type", equalTo("application/json"))
          .withHeader(HeaderAccept, equalTo(ContentJson))
          .withRequestBody(equalToJson(uploadSessionRequest))
          .willReturn(aJsonResponse()
            .withBody(uploadSessionResponse(server))))

        Content.grouped(uploadChunkSize)
          .zipWithIndex
          .foreach { t =>
            val startRange = t._2 * uploadChunkSize
            val endRange = math.min(startRange + uploadChunkSize - 1, Content.length - 1)
            val rangeHeader = s"bytes $startRange-$endRange/${Content.length}"
            val lastChunk = endRange == Content.length - 1
            server.stubFor(put(urlPathEqualTo(UploadUri))
              .withHeader("Content-Range", equalTo(rangeHeader))
              .withRequestBody(equalTo(t._1))
              .willReturn(aJsonResponse(if (lastChunk) StatusCodes.Created else StatusCodes.Accepted)
                .withBodyFile(if (lastChunk) "upload_complete_response.json" else "upload_progress_response.json")))
          }

        val fs = new OneDriveFileSystem(createConfig().copy(uploadChunkSize = uploadChunkSize))
        runOp(fs.createFile(ParentId, file, fileSource)) map { id =>
          id should be(ResolvedID)
        }
    }
  }

  it should "upload a new file" in {
    checkUploadNewFile(16384, 2048)
  }

  it should "upload a file with multiple chunks if the group size fits into the chunk size" in {
    checkUploadNewFile(2048, 1024)
  }

  it should "upload a file with multiple chunks if the group size does not fit into the chunk size" in {
    checkUploadNewFile(3333, 1024)
  }

  it should "handle an upload request that does not yield a result" in {
    val ParentId = ResolvedID.reverse
    val Content = FileTestHelper.TestData * 8
    val file = OneDriveModel.newFile(name = "someFile.dat", size = Content.length)
    val SourceUri = drivePath(s"/items/$ParentId:/someFile.dat:/createUploadSession")
    val fileSource = Source(Content.grouped(1024).toList).map(ByteString(_))

    runWithNewServer { server =>
      stubFor(post(urlPathEqualTo(SourceUri))
        .willReturn(aJsonResponse()
          .withBody(uploadSessionResponse(server))))
      server.stubFor(put(anyUrl())
        .willReturn(aJsonResponse(StatusCodes.Created.intValue)
          .withBody("{ \"foo\": \"bar\" }")))
      val fs = new OneDriveFileSystem(createConfig().copy(uploadChunkSize = 2048))

      recoverToSucceededIf[IllegalStateException](runOp(fs.createFile(ParentId, file, fileSource)))
    }
  }

  it should "upload a new file with a size of 0" in {
    val ParentId = "parent_" + ResolvedID
    val uploadPath = drivePath(s"/items/$ParentId:/$FileNameEncoded:/content")
    val expUpdateBody = readDataFile(resourceFile("/createFile.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    stubFor(put(urlPathEqualTo(uploadPath))
      .withHeader("Content-Type", equalTo("application/octet-stream"))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(absent())
      .willReturn(aJsonResponse().withBodyFile("resolve_file_response.json")))
    stubFor(patch(urlPathEqualTo(updatePath))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(equalToJson(expUpdateBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val file = OneDriveModel.newFile(id = ResolvedID, name = FileName, info = Some(TestFileInfo), size = 0)
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.createFile(ParentId, file, Source.empty)) map { result =>
      verify(patchRequestedFor(urlPathEqualTo(updatePath)))
      result should be(ResolvedID)
    }
  }

  it should "update the content and metadata of a file" in {
    val SourceUri = drivePath(s"/items/$ResolvedID/createUploadSession")
    val uploadSessionRequest = readDataFile(resourceFile("/createUploadSession.json"))
    val fileSource = Source(FileTestHelper.TestData.grouped(64).toList).map(ByteString(_))
    val fileInfo = WritableFileSystemInfo(createdDateTime = Some(Instant.parse("2021-01-31T21:21:10.123Z")),
      lastModifiedDateTime = Some(Instant.parse("2021-01-31T21:21:50.987Z")))
    val file = OneDriveModel.newFile(id = ResolvedID, name = FileName, info = Some(fileInfo),
      size = FileTestHelper.TestData.length, description = Some("Upload test file description"))

    runWithNewServer { server =>
      stubFor(post(urlPathEqualTo(SourceUri))
        .withHeader("Accept", equalTo("application/json"))
        .withRequestBody(equalToJson(uploadSessionRequest))
        .willReturn(aJsonResponse()
          .withBody(uploadSessionResponse(server))))
      val range = s"bytes 0-${FileTestHelper.TestData.length - 1}/${FileTestHelper.TestData.length()}"
      server.stubFor(put(urlPathEqualTo(UploadUri))
        .withHeader("Content-Range", equalTo(range))
        .withRequestBody(equalTo(FileTestHelper.TestData))
        .willReturn(aJsonResponse(StatusCodes.Created)
          .withBodyFile("upload_complete_response.json")))
      val fs = new OneDriveFileSystem(createConfig())

      runOp(fs.updateFileAndContent(file, fileSource)) map { _ =>
        server.verify(putRequestedFor(urlPathEqualTo(UploadUri)))
        succeed
      }
    }
  }

  it should "update the content and metadata of a file with size 0" in {
    val expBody = readDataFile(resourceFile("/createFile.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    val uploadPath = updatePath + "/content"
    stubFor(put(urlPathEqualTo(uploadPath))
      .withHeader("Content-Type", equalTo("application/octet-stream"))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(absent())
      .willReturn(aJsonResponse().withBodyFile("resolve_file_response.json")))
    stubFor(patch(urlPathEqualTo(updatePath))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val file = OneDriveModel.newFile(id = ResolvedID, name = FileName, info = Some(TestFileInfo), size = 0)
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.updateFileAndContent(file, Source.empty)) map { _ =>
      verify(putRequestedFor(urlPathEqualTo(uploadPath)))
      verify(patchRequestedFor(urlPathEqualTo(updatePath)))
      succeed
    }
  }

  it should "update the content of a file" in {
    val SourceUri = drivePath(s"/items/$ResolvedID/createUploadSession")
    val fileSource = Source(FileTestHelper.TestData.grouped(64).toList).map(ByteString(_))

    runWithNewServer { server =>
      stubFor(post(urlPathEqualTo(SourceUri))
        .withHeader("Accept", equalTo("application/json"))
        .withRequestBody(AbsentPattern.ABSENT)
        .willReturn(aJsonResponse()
          .withBody(uploadSessionResponse(server))))
      val range = s"bytes 0-${FileTestHelper.TestData.length - 1}/${FileTestHelper.TestData.length()}"
      server.stubFor(put(urlPathEqualTo(UploadUri))
        .withHeader("Content-Range", equalTo(range))
        .withRequestBody(equalTo(FileTestHelper.TestData))
        .willReturn(aJsonResponse(StatusCodes.Created)
          .withBodyFile("upload_complete_response.json")))
      val fs = new OneDriveFileSystem(createConfig())

      runOp(fs.updateFileContent(ResolvedID, FileTestHelper.TestData.length, fileSource)) map { _ =>
        server.verify(putRequestedFor(urlPathEqualTo(UploadUri)))
        succeed
      }
    }
  }

  it should "update the content of a file with a size of 0" in {
    val uploadPath = drivePath(s"/items/$ResolvedID/content")
    stubFor(put(urlPathEqualTo(uploadPath))
      .withHeader("Content-Type", equalTo("application/octet-stream"))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withRequestBody(absent())
      .willReturn(aJsonResponse().withBodyFile("resolve_file_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    runOp(fs.updateFileContent(ResolvedID, 0, Source.empty)) map { _ =>
      verify(putRequestedFor(urlPathEqualTo(uploadPath)))
      succeed
    }
  }

  it should "apply the timeout from the configuration" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_folder_response.json")
        .withFixedDelay(1000)))
    val fs = new OneDriveFileSystem(createConfig().copy(timeout = 200.millis))

    recoverToSucceededIf[TimeoutException](runOp(fs.resolveFolder(ResolvedID)))
  }

  it should "create a correctly configured HTTP sender actor" in {
    val config = createConfig()
    val TokenUri = "/oauth/token"
    val ExpiredAccessToken = "expiredToken"
    val AccessToken = "theAccessToken"
    val RefreshToken = "aRefreshToken"
    val TokenResponse =
      s"""
         |{
         |  "token_type": "bearer",
         |  "expires_in": 3600,
         |  "scope": "someScope",
         |  "access_token": "$AccessToken",
         |  "refresh_token": "$RefreshToken"
         |}
         |""".stripMargin
    val SourceUri = drivePath(s"/items/$ResolvedID/createUploadSession")
    val fileSource = Source(FileTestHelper.TestData.grouped(64).toList).map(ByteString(_))
    stubFor(post(anyUrl())
      .withHeader("Authorization", equalTo("Bearer " + ExpiredAccessToken))
      .willReturn(aResponse().withStatus(StatusCodes.Unauthorized.intValue)))

    ProxyITSpec.runWithProxy { proxySpec =>
      runWithNewServer { authServer =>
        val authConfig = OAuthConfig(tokenEndpoint = WireMockSupport.serverUri(authServer, TokenUri),
          redirectUri = "https://some.redirect.org/uri", clientID = "someClient", clientSecret = Secret("foo"),
          initTokenData = OAuthTokenData(ExpiredAccessToken, RefreshToken))
        authServer.stubFor(post(urlPathEqualTo(TokenUri))
          .willReturn(aJsonResponse(StatusCodes.OK)
            .withBody(TokenResponse)))

        runWithNewServer { uploadServer =>
          stubFor(post(urlPathEqualTo(SourceUri))
            .withHeader("Authorization", equalTo("Bearer " + AccessToken))
            .willReturn(aJsonResponse()
              .withBody(uploadSessionResponse(uploadServer))))
          uploadServer.stubFor(put(urlPathEqualTo(UploadUri))
            .withHeader("Authorization", AbsentPattern.ABSENT)
            .willReturn(aJsonResponse(StatusCodes.Created)
              .withBodyFile("upload_complete_response.json")))
          val proxy = ProxySupport.withProxy(proxySpec)
          val fs = new OneDriveFileSystem(config)

          val sender = testKit.spawn(OneDriveFileSystem.createHttpSender(config, authConfig, proxy = proxy))
          val opUpdate = fs.updateFileContent(ResolvedID, FileTestHelper.TestData.length, fileSource)
          opUpdate.run(sender) map { _ => succeed }
        }
      }
    } { queue =>
      ProxyITSpec.nextRequest(queue) // Request to get the token
      ProxyITSpec.nextRequest(queue) // The actual request.
      Future {
        succeed
      }
    }
  }

  it should "patch a folder against an empty patch spec" in {
    val FolderName = "theFolder"
    val FolderDesc = Some("Description of the test folder")
    val folder = mock[Model.Folder[String]]
    when(folder.id).thenReturn(ResolvedID)
    when(folder.name).thenReturn(FolderName)
    when(folder.description).thenReturn(FolderDesc)
    val expFolder = OneDriveModel.newFolder(id = ResolvedID, name = FolderName, description = FolderDesc)
    val fs = new OneDriveFileSystem(createConfig())

    fs.patchFolder(folder, ElementPatchSpec()) should be(expFolder)
  }

  it should "patch a folder against a defined patch spec" in {
    val PatchedName = "theUpdatedFolder"
    val createdAt = Instant.parse("2021-02-13T16:07:30.214Z")
    val lastModifiedAt = Instant.parse("2021-02-13T16:08:16.587Z")
    val fileInfo = Some(WritableFileSystemInfo(createdDateTime = Some(createdAt),
      lastModifiedDateTime = Some(lastModifiedAt)))
    val folder = OneDriveModel.newFolder(id = ResolvedID, name = "someName", info = fileInfo,
      description = Some("some description"))
    val expFolder = OneDriveModel.newFolder(id = ResolvedID, name = PatchedName, description = folder.description,
      info = fileInfo)
    val spec = ElementPatchSpec(patchName = Some(PatchedName))
    val fs = new OneDriveFileSystem(createConfig())

    fs.patchFolder(folder, spec) should be(expFolder)
  }

  it should "patch a file against an empty patch spec" in {
    val fileInfo = WritableFileSystemInfo(createdDateTime = Some(Instant.parse("2021-02-13T20:03:43.587Z")))
    val file = OneDriveModel.newFile(name = ResolvedID, size = 20210213210435L, info = Some(fileInfo))
    val fs = new OneDriveFileSystem(createConfig())

    fs.patchFile(file, ElementPatchSpec()) should be(file)
  }

  it should "patch a file against a defined patch spec" in {
    val PatchedName = "modifiedFileName.dat"
    val PatchedSize = 20210213210711L
    val FileDescription = Some("The original file description.")
    val file = mock[Model.File[String]]
    when(file.id).thenReturn(ResolvedID)
    when(file.name).thenReturn("originalName.txt")
    when(file.description).thenReturn(FileDescription)
    when(file.size).thenReturn(42L)
    val expFile = OneDriveModel.newFile(id = ResolvedID, size = PatchedSize, name = PatchedName,
      description = FileDescription)
    expFile.size should be(PatchedSize)
    val spec = ElementPatchSpec(patchName = Some(PatchedName), patchSize = Some(PatchedSize))
    val fs = new OneDriveFileSystem(createConfig())

    fs.patchFile(file, spec) should be(expFile)
  }
}
