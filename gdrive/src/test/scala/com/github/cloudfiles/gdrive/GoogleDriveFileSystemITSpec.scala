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

package com.github.cloudfiles.gdrive

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core._
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object GoogleDriveFileSystemITSpec {
  /** ID of a test file used by the tests. */
  private val TestFileID = "theTestFile"

  /** The expected fields that are requested for a file. */
  private val ExpFileFields = "id,name,size,createdTime,modifiedTime,mimeType,parents,properties,appProperties," +
    "md5Checksum,description"

  /** The expected fields that are requested for a folder's content. */
  private val ExpFolderFields = s"nextPageToken,files($ExpFileFields)"

  /** The expected query parameters when querying a folder's content. */
  private val ExpFolderQueryParams = Map("fields" -> equalTo(ExpFolderFields),
    "pageSize" -> equalTo("1000"),
    "q" -> equalTo(s"'$TestFileID' in parents and trashed = false"))

  /** Constant for the Accept header. */
  private val HeaderAccept = "Accept"

  /** Constant for the JSON content type for the accept header. */
  private val ContentJson = "application/json"

  /** The prefix for sending requests to the Google Drive API. */
  private val DriveApiPrefix = "/drive/v3/files"

  /**
   * Generates an URI path for the ''files'' resource with the given suffix.
   *
   * @param suffix the suffix (without leading slash)
   * @return the full path to manipulate a file
   */
  private def filePath(suffix: String): String = s"$DriveApiPrefix/$suffix"
}

/**
 * Integration test class for ''GoogleDriveFileSystem''.
 */
class GoogleDriveFileSystemITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with WireMockSupport with AsyncTestHelper {
  override protected val resourceRoot: String = "gdrive"

  import GoogleDriveFileSystemITSpec._

  /**
   * Returns a ''GoogleDriveConfig'' with a server URI pointing to the local
   * mock server.
   *
   * @return the initialized ''GoogleDriveConfig''
   */
  private def createConfig(): GoogleDriveConfig =
    GoogleDriveConfig(serverUri = serverBaseUri)

  /**
   * Executes the given file system operation against the mock server.
   *
   * @param op the operation to execute
   * @tparam A the result type of the operation
   * @return a future with the result of the operation
   */
  private def runOp[A](op: FileSystem.Operation[A]): Future[A] = {
    val httpActor = spawn(HttpRequestSender(serverBaseUri))
    op.run(httpActor)
  }

  "GoogleDriveFileSystem" should "delete a folder" in {
    val deletePath = filePath(TestFileID)
    stubFor(delete(urlPathEqualTo(deletePath))
      .willReturn(aResponse().withStatus(StatusCodes.NoContent.intValue)))
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.deleteFolder(TestFileID)))
    verify(deleteRequestedFor(urlPathEqualTo(deletePath)))
  }

  it should "delete a file" in {
    val deletePath = filePath(TestFileID)
    stubFor(delete(urlPathEqualTo(deletePath))
      .willReturn(aResponse().withStatus(StatusCodes.NoContent.intValue)))
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.deleteFile(TestFileID)))
    verify(deleteRequestedFor(urlPathEqualTo(deletePath)))
  }

  it should "discard the entities of requests where the response does not matter" in {
    implicit val ec: ExecutionContext = system.executionContext
    stubSuccess(WireMockSupport.NoAuthFunc)
    val fs = new GoogleDriveFileSystem(createConfig())

    // Execute a number of operations. If the entities are not discarded, the
    // HTTP pipeline will block, and we will run into a timeout.
    val futResults = (1 to 16) map { idx =>
      val id = s"id$idx"
      runOp(fs.deleteFolder(id))
    }
    futureResult(Future.sequence(futResults))
  }

  it should "return the constant root ID if no root path is specified" in {
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.rootID)) should be("root")
  }

  it should "resolve a file" in {
    stubFor(get(urlPathEqualTo(filePath(TestFileID)))
      .withQueryParam("fields", equalTo(ExpFileFields))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .willReturn(aJsonResponse().withBodyFile("resolveFileResponse.json")))
    val expGoogleFile = GoogleDriveJsonProtocol.File(id = "file_id-J9die95gBb_123456987", name = "test.txt",
      mimeType = "text/plain", parents = List("parent_id_OxrHyqtqWLAM7EY"), size = Some("323"),
      createdTime = Instant.parse("2021-08-17T20:01:53.123Z"),
      modifiedTime = Instant.parse("2021-08-17T20:02:24.456Z"),
      description = Some("Description of test file."),
      properties = Some(Map("foo" -> "bar", "test" -> "true")),
      appProperties = Some(Map("appFoo" -> "appBar", "appTest" -> "true")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val file = futureResult(runOp(fs.resolveFile(TestFileID)))
    file.googleFile should be(expGoogleFile)
  }

  it should "resolve a folder" in {
    stubFor(get(urlPathEqualTo(filePath(TestFileID)))
      .withQueryParam("fields", equalTo(ExpFileFields))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .willReturn(aJsonResponse().withBodyFile("resolveFolderResponse.json")))
    val expGoogleFile = GoogleDriveJsonProtocol.File(id = "folder_id-AECVBSOxrHyqtqTest", name = "test",
      mimeType = "application/vnd.google-apps.folder", parents = List("parent_id_OxrHyqtqWLAM7EY"), size = None,
      createdTime = Instant.parse("2021-08-18T19:44:29.321Z"),
      modifiedTime = Instant.parse("2021-08-18T19:44:51.987Z"),
      description = None, properties = None, appProperties = None)
    val fs = new GoogleDriveFileSystem(createConfig())

    val folder = futureResult(runOp(fs.resolveFolder(TestFileID)))
    folder.googleFile should be(expGoogleFile)
  }

  it should "return a failed future if a file to resolve is actually a folder" in {
    stubFor(get(urlPathEqualTo(filePath(TestFileID)))
      .willReturn(aJsonResponse().withBodyFile("resolveFolderResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val exception = expectFailedFuture[IllegalArgumentException](runOp(fs.resolveFile(TestFileID)))
    exception.getMessage should include(TestFileID)
  }

  it should "return a failed future if a folder to resolve is actually a file" in {
    stubFor(get(urlPathEqualTo(filePath(TestFileID)))
      .willReturn(aJsonResponse().withBodyFile("resolveFileResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val exception = expectFailedFuture[IllegalArgumentException](runOp(fs.resolveFolder(TestFileID)))
    exception.getMessage should include(TestFileID)
  }

  /**
   * Checks whether an object representing the content of a folder contains the
   * expected elements.
   *
   * @param content        the content to check
   * @param expFileCount   the expected number of files
   * @param expFolderCount the expected number of folders
   */
  private def checkFolderContent(content: Model.FolderContent[String, GoogleDriveModel.GoogleDriveFile,
    GoogleDriveModel.GoogleDriveFolder], expFileCount: Int, expFolderCount: Int): Unit = {
    content.folderID should be(TestFileID)
    content.files should have size expFileCount
    (1 to expFileCount) foreach { idx =>
      val file = content.files(s"file_id-$idx")
      file.name should be(s"file$idx.txt")
      file.size should be(1000 + idx)
    }
    content.folders should have size expFolderCount
    (1 to expFolderCount) foreach { idx =>
      val folder = content.folders(s"folder_id-$idx")
      folder.name should be(s"folder$idx")
    }
  }

  it should "query the content of a folder" in {
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParams(ExpFolderQueryParams.asJava)
      .willReturn(aJsonResponse().withBodyFile("folderContentResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val content = futureResult(runOp(fs.folderContent(TestFileID)))
    checkFolderContent(content, expFileCount = 3, expFolderCount = 2)
  }

  it should "query the content of a folder that is split over multiple pages" in {
    val paramsWithNextPageToken = ExpFolderQueryParams + ("pageToken" -> equalTo("nextPage-12345"))
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParams(paramsWithNextPageToken.asJava)
      .willReturn(aJsonResponse().withBodyFile("folderContentResponsePage2.json")))
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParams(ExpFolderQueryParams.asJava)
      .withQueryParam("pageToken", absent())
      .willReturn(aJsonResponse().withBodyFile("folderContentResponsePage1.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val content = futureResult(runOp(fs.folderContent(TestFileID)))
    checkFolderContent(content, expFileCount = 4, expFolderCount = 3)
  }

  it should "download a file" in {
    stubFor(get(urlPathEqualTo(filePath(TestFileID)))
      .withQueryParam("alt", equalTo("media"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(FileTestHelper.TestData)
        .withHeader("Content-Type", "text/plain; charset=UTF-8")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val entity = futureResult(runOp(fs.downloadFile(TestFileID)))
    entity.contentType should be(ContentTypes.`text/plain(UTF-8)`)
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val data = futureResult(entity.dataBytes.runWith(sink))
    data.utf8String should be(FileTestHelper.TestData)
  }
}
