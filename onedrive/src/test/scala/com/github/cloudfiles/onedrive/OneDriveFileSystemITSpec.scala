/*
 * Copyright 2020-2021 The Developers Team.
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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.StatusCodes
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.github.cloudfiles.core.http.MultiHostExtension
import com.github.cloudfiles.core._
import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

object OneDriveFileSystemITSpec {
  /** Test OneDrive ID. */
  private val DriveID = "1234567890"

  /** The base path of the server API. */
  private val ServerPath = "/v1.0/me/drives"

  /** The root path within OneDrive. */
  private val RootPath = "/my/data"

  /** A test ID that appears in some test server responses. */
  private val ResolvedID = "some_test_id"

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
class OneDriveFileSystemITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar with WireMockSupport with AsyncTestHelper with FileTestHelper {
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
      .willReturn(aJsonResponse()
        .withBodyFile("resolve_response.json")))
  }

  "OneDriveFileSystem" should "determine the root ID for an undefined root path" in {
    stubResolvePath("/root")
    val fs = new OneDriveFileSystem(createConfig().copy(optRootPath = None))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "determine the root ID if a root path is specified" in {
    stubResolvePath(s"/root:$RootPath:")
    val fs = new OneDriveFileSystem(createConfig())

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "handle a trailing slash in the server URI" in {
    stubResolvePath(s"/root:$RootPath:")
    val config = createConfig()
    val fs = new OneDriveFileSystem(config.copy(serverUri = config.serverUri + "/"))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "handle a missing leading slash in the root path" in {
    stubResolvePath(s"/root:$RootPath:")
    val config = createConfig()
    val fs = new OneDriveFileSystem(config.copy(optRootPath = Some(RootPath.drop(1))))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "resolve a path for an undefined root path" in {
    val path = "/the/path/to/resolve"
    stubResolvePath(s"/root:$path:")
    val fs = new OneDriveFileSystem(createConfig().copy(optRootPath = None))

    val pathID = futureResult(runOp(fs.resolvePath(path)))
    pathID should be(ResolvedID)
  }

  it should "resolve a path if a root path is specified" in {
    val path = "/the/path/to/resolve/from/root/foo.txt"
    stubResolvePath(s"/root:$RootPath$path:")
    val fs = new OneDriveFileSystem(createConfig())

    val pathID = futureResult(runOp(fs.resolvePath(path)))
    pathID should be(ResolvedID)
  }

  it should "resolve a path that does not start with a slash" in {
    val path = "path/no/slash"
    stubResolvePath(s"/root:$RootPath/$path:")
    val fs = new OneDriveFileSystem(createConfig())

    val pathID = futureResult(runOp(fs.resolvePath(path)))
    pathID should be(ResolvedID)
  }

  it should "resolve a folder by its ID" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_folder_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    val folder = futureResult(runOp(fs.resolveFolder(ResolvedID)))
    folder.id should be(ResolvedID)
    folder.name should be("data")
    folder.description should be(null)
    folder.folderData.childCount should be(9)
    folder.item.fileSystemInfo.createdDateTime should be(Instant.parse("2019-11-12T14:32:50.8Z"))
  }

  it should "check whether the ID points to a folder when resolving it" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    expectFailedFuture[IllegalArgumentException](runOp(fs.resolveFolder(ResolvedID)))
  }

  it should "resolve a file by its ID" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    val file = futureResult(runOp(fs.resolveFile(ResolvedID)))
    file.id should be(ResolvedID)
    file.name should be("test.txt")
    file.size should be(327)
    file.fileData.mimeType should be("application/octet-stream")
    file.fileData.hashes.sha1Hash should be(Some("319D8515AC0683C7EA6AF60A547E142141F11BF5"))
  }

  it should "check whether the ID points to a file when resolving it" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_folder_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    expectFailedFuture[IllegalArgumentException](runOp(fs.resolveFile(ResolvedID)))
  }

  it should "return the content of a folder" in {
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID/children")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("folder_children_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    val content = futureResult(runOp(fs.folderContent(ResolvedID)))
    content.folderID should be(ResolvedID)
    content.folders should have size 2
    content.folders("xxxyyyzzz1234567!7193").name should be("subFolder1")
    content.folders("xxxyyyzzz1234567!4891").name should be("subFolder2")
    content.files should have size 2
    content.files("xxxyyyzzz1234567!26990").name should be("data.json")
    content.files("xxxyyyzzz1234567!26988").name should be("info.txt")
  }

  it should "return the content of a folder split onto multiple pages" in {
    val nextUri = drivePath("/next/folder/children")
    val folder1Data = readDataFile(resourceFile("/__files/folder_children_with_next_response.json"))
      .replace("${next.folder}", serverUri(nextUri))
    stubFor(get(urlPathEqualTo(drivePath(s"/items/$ResolvedID/children")))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBody(folder1Data)))
    stubFor(get(urlPathEqualTo(nextUri))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("folder_children_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    val content = futureResult(runOp(fs.folderContent(ResolvedID)))
    content.folderID should be(ResolvedID)
    content.folders should have size 2
    content.files should have size 4
    content.files("xxxyyyzzz1234567!26990").name should be("data.json")
    content.files("xxxyyyzzz1234567!26124").name should be("file (2).mp3")
  }

  it should "delete a folder" in {
    val deletePath = drivePath(s"/items/$ResolvedID")
    stubFor(delete(urlPathEqualTo(deletePath))
      .willReturn(aResponse().withStatus(StatusCodes.NoContent.intValue)))
    val fs = new OneDriveFileSystem(createConfig())

    futureResult(runOp(fs.deleteFolder(ResolvedID)))
    verify(deleteRequestedFor(urlPathEqualTo(deletePath)))
  }

  it should "discard the entities of requests where the response does not matter" in {
    implicit val ec: ExecutionContext = system.executionContext
    stubSuccess(WireMockSupport.NoAuthFunc)
    val fs = new OneDriveFileSystem(createConfig())

    // Execute a number of operations. If the entities are not discarded, the
    // HTTP pipeline will block, and we will run into a timeout.
    val futResults = (1 to 16) map { idx =>
      val id = s"id$idx"
      runOp(fs.deleteFolder(id))
    }
    futureResult(Future.sequence(futResults))
  }

  it should "delete a file" in {
    val deletePath = drivePath(s"/items/$ResolvedID")
    stubFor(delete(urlPathEqualTo(deletePath))
      .willReturn(aResponse().withStatus(StatusCodes.NoContent.intValue)))
    val fs = new OneDriveFileSystem(createConfig())

    futureResult(runOp(fs.deleteFile(ResolvedID)))
    verify(deleteRequestedFor(urlPathEqualTo(deletePath)))
  }

  it should "create a new folder" in {
    val ParentId = "someParent"
    val expBody = readDataFile(resourceFile("/createFolder.json"))
    stubFor(post(urlPathEqualTo(drivePath(s"/items/$ParentId/children")))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.Created)
        .withBodyFile("resolve_folder_response.json")))
    val fsInfo =
      OneDriveJsonProtocol.WritableFileSystemInfo(createdDateTime = Some(Instant.parse("2021-01-23T20:07:10.113Z")))
    val folder = OneDriveModel.newFolder("cloud-files", "This is the description of the test folder.",
      Some(fsInfo))
    val fs = new OneDriveFileSystem(createConfig())

    val folderId = futureResult(runOp(fs.createFolder(ParentId, folder)))
    folderId should be(ResolvedID)
  }

  it should "create a new folder from another Folder implementation" in {
    val folder = mock[Model.Folder[String]]
    when(folder.name).thenReturn("cloud-files")
    val expBody = readDataFile(resourceFile("/createFolderMinimum.json"))
    stubFor(post(urlPathEqualTo(drivePath(s"/items/$ResolvedID/children")))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.Created)
        .withBodyFile("resolve_folder_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    val folderId = futureResult(runOp(fs.createFolder(ResolvedID, folder)))
    folderId should be(ResolvedID)
  }

  it should "update a folder" in {
    val expBody = readDataFile(resourceFile("/createFolderMinimum.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    stubFor(patch(urlPathEqualTo(updatePath))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_folder_response.json")))
    val folder = OneDriveModel.updateFolder(ResolvedID, "cloud-files",
      info = Some(OneDriveJsonProtocol.WritableFileSystemInfo()))
    val fs = new OneDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateFolder(folder)))
    verify(patchRequestedFor(urlPathEqualTo(updatePath)))
  }

  it should "update a file" in {
    val expBody = readDataFile(resourceFile("/createFile.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    stubFor(patch(urlPathEqualTo(updatePath))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val fsInfo = OneDriveJsonProtocol.WritableFileSystemInfo(
      lastModifiedDateTime = Some(Instant.parse("2021-01-30T15:54:20.224Z")),
      lastAccessedDateTime = Some(Instant.parse("2021-01-30T15:54:48.448Z")))
    val file = OneDriveModel.updateFile(ResolvedID, name = "cloudFiles.adoc", info = Some(fsInfo))
    val fs = new OneDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateFile(file)))
    verify(patchRequestedFor(urlPathEqualTo(updatePath)))
  }

  it should "update a file from another File implementation" in {
    val file = mock[Model.File[String]]
    when(file.id).thenReturn(ResolvedID)
    when(file.description).thenReturn("A test file.")
    val expBody = readDataFile(resourceFile("/createFileMinimum.json"))
    val updatePath = drivePath(s"/items/$ResolvedID")
    stubFor(patch(urlPathEqualTo(updatePath))
      .withRequestBody(equalToJson(expBody))
      .willReturn(aJsonResponse(StatusCodes.OK)
        .withBodyFile("resolve_file_response.json")))
    val fs = new OneDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateFile(file)))
    verify(patchRequestedFor(urlPathEqualTo(updatePath)))
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

      val source = futureResult(runOp(fs.downloadFile(ResolvedID)))
      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      val content = futureResult(source.dataBytes.runWith(sink))
      content.utf8String should be(FileTestHelper.TestData)
    }
  }

  it should "handle a missing Location header when downloading a file" in {
    val path = drivePath(s"/items/$ResolvedID/content")
    stubFor(get(urlPathEqualTo(path))
      .willReturn(aResponse().withStatus(StatusCodes.Found.intValue)
        .withBody("Missing Location header")))
    val fs = new OneDriveFileSystem(createConfig())

    val ex = expectFailedFuture[IllegalStateException](runOp(fs.downloadFile(ResolvedID)))
    ex.getMessage should include(path)
  }
}
