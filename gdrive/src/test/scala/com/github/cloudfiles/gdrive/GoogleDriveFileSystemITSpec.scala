/*
 * Copyright 2020-2023 The Developers Team.
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

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.{ContentTypes, StatusCode, StatusCodes}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core._
import com.github.cloudfiles.core.delegate.ElementPatchSpec
import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.{MultiHostExtension, UriEncodingHelper}
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.StringValuePattern
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant
import java.util
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, TimeoutException}

object GoogleDriveFileSystemITSpec {
  /** ID of a test file used by the tests. */
  private val TestFileID = "theTestFile"

  /** The expected fields that are requested for a file. */
  private val ExpFileFields = "id,name,size,createdTime,modifiedTime,mimeType,parents,properties,appProperties," +
    "md5Checksum,description,trashed,trashedTime"

  /** The expected fields that are requested for a folder's content. */
  private val ExpFolderFields = s"nextPageToken,files($ExpFileFields)"

  /** The expected fields that are requested for a resolve query. */
  private val ExpResolveFields = "nextPageToken,files(id)"

  /** The expected query parameters when querying a folder's content. */
  private val ExpFolderQueryParams = Map("fields" -> equalTo(ExpFolderFields),
    "pageSize" -> equalTo("1000"),
    "q" -> equalTo(s"'$TestFileID' in parents and trashed = false"))

  /** Constant for the Accept header. */
  private val HeaderAccept = "Accept"

  /** Constant for the Content-Type header. */
  private val HeaderContent = "Content-Type"

  /** Constant for the Content-Length header. */
  private val HeaderLength = "Content-Length"

  /** Constant for the Location header. */
  private val HeaderLocation = "Location"

  /** Constant for the JSON content type for the accept header. */
  private val ContentJson = "application/json"

  /** The prefix for sending requests to the Google Drive API. */
  private val DriveApiPrefix = "/drive/v3/files"

  /** The prefix for sending file upload requests to the Google Drive API. */
  private val UploadApiPrefix = s"/upload$DriveApiPrefix"

  /** The length of the test content for file uploads. */
  private val TestFileContentLength = FileTestHelper.TestData.length

  /** A test upload ID to be used for file upload requests. */
  private val UploadID = "someComplexUploadID"

  /** A test upload URI with all the required query parameters. */
  private val UploadUri = s"$UploadApiPrefix?uploadType=resumable&upload_id=$UploadID"

  /**
   * Generates an URI path for the ''files'' resource with the given suffix.
   *
   * @param suffix the suffix (without leading slash)
   * @return the full path to manipulate a file
   */
  private def filePath(suffix: String): String = s"$DriveApiPrefix/$suffix"

  /**
   * Provides a convenience matcher to check a request body against the JSON
   * representation of the given writable file.
   *
   * @param file the reference file
   * @return the matcher to check against this file
   */
  private def equalToFile(file: GoogleDriveJsonProtocol.WritableFile): StringValuePattern =
    equalToJson(file.toJson.toString())

  /**
   * Generates a ''Model.Folder'' object with the properties provided.
   *
   * @param fid     the folder ID
   * @param fName   the folder name
   * @param fDesc   the folder description
   * @param fCreate the creation time
   * @param fModify the last modification time
   * @return the folder with these properties
   */
  private def modelFolder(fid: String = null, fName: String = null, fDesc: Option[String] = None,
                          fCreate: Instant = null, fModify: Instant = null): Model.Folder[String] =
    new Model.Folder[String] {
      override val id: String = fid
      override val name: String = fName
      override val description: Option[String] = fDesc
      override val createdAt: Instant = fCreate
      override val lastModifiedAt: Instant = fModify
    }

  /**
   * Generates a ''Model.File'' object with the properties provided.
   *
   * @param fid     the file ID
   * @param fName   the file name
   * @param fDesc   the file description
   * @param fCreate the creation time
   * @param fModify the last modification time
   * @param fSize   the file size
   * @return the file with these properties
   */
  private def modelFile(fid: String = null, fName: String = null, fDesc: Option[String] = None,
                        fCreate: Instant = null, fModify: Instant = null, fSize: Long = 0L): Model.File[String] =
    new Model.File[String] {
      override val id: String = fid
      override val name: String = fName
      override val description: Option[String] = fDesc
      override val createdAt: Instant = fCreate
      override val lastModifiedAt: Instant = fModify
      override val size: Long = fSize
    }

  /**
   * Returns a source with test content for a file upload.
   *
   * @return the source with test file content
   */
  private def testFileContent: Source[ByteString, NotUsed] =
    Source(FileTestHelper.testBytes().grouped(32).map(ByteString.apply).toList)

  /**
   * Generates a JSON fragment with the given file IDs that can be added to a
   * response for a resolve query.
   *
   * @param ids the IDs to add to the response
   * @return the response fragment with resolved IDs
   */
  private def generateResolvedIDs(ids: String*): String =
    ids.map(id => """{ "id": """" + id + "\"}").mkString(",")

  /**
   * Generates a response for a resolve query based on the given files fragment
   * with an optional token for the next page.
   *
   * @param files        the fragment with the files in the response
   * @param optNextToken the optional token for the next page
   * @return the JSON response for a resolve query
   */
  private def resolveResponse(files: String, optNextToken: Option[String] = None): String = {
    val tokenFragment = optNextToken.map(token => "\"nextPageToken\": \"" + token + "\",") getOrElse ""
    s"""{
       |  $tokenFragment
       |  "files": [
       |    $files
       |  ]
       |}""".stripMargin
  }

  /**
   * Helper function to convert a Scala map with query parameters to a Java map
   * as needed by WireMock. (We do not use Scala's collection converters here
   * to avoid problems with cross compilation.)
   *
   * @param params the map with parameters to convert
   * @return the Java map with query parameters
   */
  private def toQueryParams(params: Map[String, StringValuePattern]): java.util.Map[String, StringValuePattern] = {
    val javaMap = new util.HashMap[String, StringValuePattern]
    params foreach { e =>
      javaMap.put(e._1, e._2)
    }
    javaMap
  }
}

/**
 * Integration test class for ''GoogleDriveFileSystem''.
 */
class GoogleDriveFileSystemITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with WireMockSupport with AsyncTestHelper {
  override protected val resourceRoot: String = "gdrive"

  import GoogleDriveFileSystemITSpec._

  /**
   * Convenience function to create a ''GoogleDriveConfig'' with defaults.
   *
   * @param timeout        the timeout
   * @param optRoot        an optional root path
   * @param includeTrashed flag to included trashed files
   * @return the initialized ''GoogleDriveConfig''
   */
  private def createConfig(timeout: Timeout = GoogleDriveConfig.DefaultTimeout, optRoot: Option[String] = None,
                           includeTrashed: Boolean = false):
  GoogleDriveConfig =
    GoogleDriveConfig(timeout = timeout, optRootPath = optRoot, includeTrashed = includeTrashed,
      serverUri = UriEncodingHelper.withTrailingSeparator(serverBaseUri))

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
   * Helper function to stub a file upload request. The headers, query
   * parameters, and the body of a test upload request are specified.
   * Optionally, the status code for the response can be configured.
   *
   * @param status the response status code
   * @return the configured ''MappingBuilder''
   */
  private def stubUploadRequest(status: StatusCode = StatusCodes.OK): Unit = {
    stubFor(put(urlPathEqualTo(UploadApiPrefix))
      .withQueryParam("uploadType", equalTo("resumable"))
      .withQueryParam("upload_id", equalTo(UploadID))
      .withHeader(HeaderLength, equalTo(TestFileContentLength.toString))
      .withRequestBody(equalTo(FileTestHelper.TestData))
      .willReturn(aJsonResponse(status).withBodyFile("createFileResponse.json")))
  }

  /**
   * Verifies that a correct upload request has been executed. Unfortunately,
   * the declarations from the stubbing have to be repeated.
   */
  private def verifyUploadRequest(): Unit = {
    waitAndVerify(testKit, 2) {
      putRequestedFor(urlPathEqualTo(UploadApiPrefix))
        .withQueryParam("uploadType", equalTo("resumable"))
        .withQueryParam("upload_id", equalTo(UploadID))
        .withHeader(HeaderLength, equalTo(TestFileContentLength.toString))
        .withRequestBody(equalTo(FileTestHelper.TestData))
    }
  }

  /**
   * Helper function to stub a request to resolve a path component.
   *
   * @param parent         the parent folder
   * @param fileName       the name of the file to resolve
   * @param response       the response to return
   * @param optPageToken   optional token for the next page
   * @param foldersOnly    flag whether the request should filter for folders
   * @param includeTrashed flag whether to include trashed files
   */
  private def stubResolveRequest(parent: String, fileName: String, response: String,
                                 optPageToken: Option[String] = None, foldersOnly: Boolean = false,
                                 includeTrashed: Boolean = false): Unit = {
    val filterParam = s"'$parent' in parents and name = '$fileName'"
    val filterParamWithFolders = if (foldersOnly) filterParam + " and mimeType = 'application/vnd.google-apps.folder'"
    else filterParam
    val filterParamsWithTrash = if (includeTrashed) filterParamWithFolders
    else filterParamWithFolders + " and trashed = false"
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParam("fields", equalTo(ExpResolveFields))
      .withQueryParam("pageSize", equalTo("1000"))
      .withQueryParam("q", equalTo(filterParamsWithTrash))
      .withQueryParam("pageToken", optPageToken.fold(absent())(token => equalTo(token)))
      .willReturn(aJsonResponse().withBody(response)))
  }

  /**
   * Stubs a request to resolve a path component that does not yield any
   * results. This means that this component cannot be resolved.
   *
   * @param parent      the parent folder
   * @param fileName    the name of the file to resolve
   * @param foldersOnly flag whether the request should filter for folders
   */
  private def stubFailedResolveRequest(parent: String, fileName: String, foldersOnly: Boolean = false): Unit = {
    stubResolveRequest(parent, fileName, resolveResponse(""), foldersOnly = foldersOnly)
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
      .withQueryParams(toQueryParams(ExpFolderQueryParams))
      .willReturn(aJsonResponse().withBodyFile("folderContentResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val content = futureResult(runOp(fs.folderContent(TestFileID)))
    checkFolderContent(content, expFileCount = 3, expFolderCount = 2)
  }

  it should "query the content of a folder that is split over multiple pages" in {
    val paramsWithNextPageToken = ExpFolderQueryParams + ("pageToken" -> equalTo("nextPage-12345"))
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParams(toQueryParams(paramsWithNextPageToken))
      .willReturn(aJsonResponse().withBodyFile("folderContentResponsePage2.json")))
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParams(toQueryParams(ExpFolderQueryParams))
      .withQueryParam("pageToken", absent())
      .willReturn(aJsonResponse().withBodyFile("folderContentResponsePage1.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val content = futureResult(runOp(fs.folderContent(TestFileID)))
    checkFolderContent(content, expFileCount = 4, expFolderCount = 3)
    val trashedFolder = content.folders("folder_id-3")
    trashedFolder.googleFile.trashed shouldBe true
    trashedFolder.googleFile.trashedTime should be(Some(Instant.parse("2021-09-10T19:09:41.110Z")))
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

  /**
   * Helper function to check the creation of a folder. Checks whether a
   * request with the correct content is sent, and the response is correctly
   * evaluated.
   *
   * @param srcFolder          the source folder defining the new folder
   * @param expectedProperties the expected properties in the request
   */
  private def checkCreateFolder(srcFolder: Model.Folder[String],
                                expectedProperties: GoogleDriveJsonProtocol.WritableFile): Unit = {
    stubFor(post(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderContent, equalTo(ContentJson))
      .withRequestBody(equalToFile(expectedProperties))
      .willReturn(aJsonResponse(StatusCodes.Created).withBodyFile("createFolderResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    val folderID = futureResult(runOp(fs.createFolder(TestFileID, srcFolder)))
    folderID should be("folder_id-AECVBSOxrHyqtqTest")
  }

  it should "create a folder from a model folder" in {
    val FolderName = "MyNewFolder"
    val FolderDesc = "Description of my new folder"
    val creationTime = Instant.parse("2021-08-21T14:41:48.123Z")
    val modifiedTime = Instant.parse("2021-08-21T14:42:08.124Z")
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = Some(FolderName),
      createdTime = Some(creationTime), modifiedTime = Some(modifiedTime), properties = None, appProperties = None,
      description = Some(FolderDesc), parents = Some(List(TestFileID)),
      mimeType = Some("application/vnd.google-apps.folder"))
    val srcFolder = modelFolder(fName = FolderName, fDesc = Some(FolderDesc), fCreate = creationTime,
      fModify = modifiedTime)

    checkCreateFolder(srcFolder, expectedProperties)
  }

  it should "create a folder from a model folder with minimum properties" in {
    val FolderName = "MyNewFolder"
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = Some(FolderName),
      createdTime = None, modifiedTime = None, properties = None, appProperties = None,
      description = None, parents = Some(List(TestFileID)),
      mimeType = Some("application/vnd.google-apps.folder"))
    val srcFolder = modelFolder(fName = FolderName)

    checkCreateFolder(srcFolder, expectedProperties)
  }

  it should "create a folder from a GoogleDrive folder" in {
    val googleFile = GoogleDriveJsonProtocol.File(id = null, name = "theNewFolder", parents = Nil,
      createdTime = Instant.parse("2021-08-21T20:01:24.547Z"),
      modifiedTime = Instant.parse("2021-08-21T20:06:35.456Z"),
      description = Some("Folder description"), mimeType = "someMimeType", size = None,
      properties = Some(Map("property" -> "test")), appProperties = Some(Map("appProp" -> "yes")))
    val srcFolder = GoogleDriveModel.GoogleDriveFolder(googleFile)
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = Some(googleFile.name),
      createdTime = Some(srcFolder.createdAt), modifiedTime = Some(srcFolder.lastModifiedAt),
      description = googleFile.description, properties = googleFile.properties,
      appProperties = googleFile.appProperties, mimeType = Some("application/vnd.google-apps.folder"),
      parents = Some(List(TestFileID)))

    checkCreateFolder(srcFolder, expectedProperties)
  }

  /**
   * Helper function to check whether a folder is correctly updated from the
   * properties of another folder.
   *
   * @param srcFolder          the folder with the properties to update
   * @param expectedProperties the properties expected in the update request
   */
  private def checkUpdateFolder(srcFolder: Model.Folder[String],
                                expectedProperties: GoogleDriveJsonProtocol.WritableFile): Unit = {
    stubFor(patch(urlPathEqualTo(filePath(TestFileID)))
      .willReturn(aJsonResponse(StatusCodes.Created).withBodyFile("resolveFolderResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateFolder(srcFolder)))
    verify(patchRequestedFor(urlPathEqualTo(filePath(TestFileID)))
      .withHeader(HeaderContent, equalTo(ContentJson))
      .withRequestBody(equalToFile(expectedProperties)))
  }

  it should "update a folder from a model folder" in {
    val srcFolder = modelFolder(fid = TestFileID, fDesc = Some("Updated folder description"),
      fCreate = Instant.parse("2021-08-22T15:51:47.369Z"),
      fModify = Instant.parse("2021-08-22T15:52:14.741Z"))
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = None, mimeType = None, parents = None,
      createdTime = Some(srcFolder.createdAt), modifiedTime = Some(srcFolder.lastModifiedAt),
      description = srcFolder.description, properties = None, appProperties = None)

    checkUpdateFolder(srcFolder, expectedProperties)
  }

  it should "update a folder from a GoogleDrive folder" in {
    val googleFile = GoogleDriveJsonProtocol.File(id = TestFileID, name = null, mimeType = "someMimeType",
      parents = List("someParent"), createdTime = null, description = Some("new description"),
      modifiedTime = Instant.parse("2021-08-22T16:09:22.258Z"), size = None,
      properties = Some(Map("newProperty" -> "newValue")),
      appProperties = Some(Map("newAppProperty" -> "anotherNewValue")))
    val srcFolder = GoogleDriveModel.GoogleDriveFolder(googleFile)
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = None, mimeType = None, parents = None,
      createdTime = None, modifiedTime = Some(srcFolder.lastModifiedAt), description = srcFolder.description,
      properties = googleFile.properties, appProperties = googleFile.appProperties)

    checkUpdateFolder(srcFolder, expectedProperties)
  }

  it should "create a new file" in {
    val googleFile = GoogleDriveJsonProtocol.File(id = null, name = "NewFile.dat", mimeType = "text/plain",
      parents = List("ignoredParent"), createdTime = Instant.parse("2021-08-22T16:48:16.654Z"),
      modifiedTime = Instant.parse("2021-08-22T16:48:50.987Z"), description = Some("a new file"),
      size = Some(TestFileContentLength.toString), properties = Some(Map("property" -> "test")),
      appProperties = Some(Map("appProperty" -> "appTest")))
    val srcFile = GoogleDriveModel.GoogleDriveFile(googleFile)
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = Some(srcFile.name),
      mimeType = Some(googleFile.mimeType), parents = Some(List(TestFileID)), description = googleFile.description,
      createdTime = Some(srcFile.createdAt), modifiedTime = Some(srcFile.lastModifiedAt),
      properties = googleFile.properties, appProperties = googleFile.appProperties)
    stubFor(post(urlPathEqualTo(UploadApiPrefix))
      .withQueryParam("uploadType", equalTo("resumable"))
      .withHeader(HeaderContent, equalTo(ContentJson))
      .withRequestBody(equalToFile(expectedProperties))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader(HeaderLocation, serverUri(UploadUri))))
    stubUploadRequest()
    val fs = new GoogleDriveFileSystem(createConfig())

    val fileID = futureResult(runOp(fs.createFile(TestFileID, srcFile, testFileContent)))
    fileID should be("file_id-J9die95gBb_123456987")
  }

  it should "handle a missing Location header when creating a new file" in {
    stubFor(post(urlPathEqualTo(UploadApiPrefix))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val fs = new GoogleDriveFileSystem(createConfig())

    val opCreate = fs.createFile(TestFileID, GoogleDriveModel.newFile(name = "myNewFile"), testFileContent)
    val exception = expectFailedFuture[IllegalStateException](runOp(opCreate))
    exception.getMessage should include("'Location' header")
  }

  it should "update the content of a file" in {
    stubFor(patch(urlPathEqualTo(s"$UploadApiPrefix/$TestFileID"))
      .withQueryParam("uploadType", equalTo("resumable"))
      .withRequestBody(absent())
      .withHeader(HeaderContent, absent())
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader(HeaderLocation, serverUri(UploadUri))))
    stubUploadRequest()
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateFileContent(TestFileID, TestFileContentLength, testFileContent)))
    verifyUploadRequest()
  }

  it should "evaluate the response status when updating the content of a file" in {
    stubFor(patch(urlPathEqualTo(s"$UploadApiPrefix/$TestFileID"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader(HeaderLocation, serverUri(UploadUri))))
    stubUploadRequest(status = StatusCodes.InternalServerError)
    val fs = new GoogleDriveFileSystem(createConfig())

    val exception = expectFailedFuture[FailedResponseException](runOp(fs.updateFileContent(TestFileID,
      TestFileContentLength, testFileContent)))
    exception.response.status should be(StatusCodes.InternalServerError)
  }

  it should "update both the content and the metadata of a file" in {
    val googleFile = GoogleDriveJsonProtocol.File(id = TestFileID, name = null, mimeType = "text/plain",
      parents = List("ignoredParent"), createdTime = Instant.parse("2021-08-28T18:29:40.543Z"),
      modifiedTime = Instant.parse("2021-08-28T18:30:02.876Z"), description = Some("a new file"),
      size = Some(TestFileContentLength.toString), properties = Some(Map("property" -> "test")),
      appProperties = Some(Map("appProperty" -> "appTest")))
    val srcFile = GoogleDriveModel.GoogleDriveFile(googleFile)
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = None,
      mimeType = Some(googleFile.mimeType), parents = None, description = googleFile.description,
      createdTime = Some(srcFile.createdAt), modifiedTime = Some(srcFile.lastModifiedAt),
      properties = googleFile.properties, appProperties = googleFile.appProperties)
    stubFor(patch(urlPathEqualTo(s"$UploadApiPrefix/$TestFileID"))
      .withQueryParam("uploadType", equalTo("resumable"))
      .withHeader(HeaderContent, equalTo(ContentJson))
      .withRequestBody(equalToFile(expectedProperties))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader(HeaderLocation, serverUri(UploadUri))))
    stubUploadRequest()
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateFileAndContent(srcFile, testFileContent)))
    verifyUploadRequest()
  }

  it should "update the metadata of a file" in {
    val googleFile = GoogleDriveJsonProtocol.File(id = TestFileID, name = null, mimeType = "someMimeType",
      parents = List("someParent"), createdTime = null, description = Some("new description"),
      modifiedTime = Instant.parse("2021-08-28T19:05:55.347Z"), size = None,
      properties = Some(Map("newProperty" -> "newValue")),
      appProperties = Some(Map("newAppProperty" -> "anotherNewValue")))
    val srcFile = GoogleDriveModel.GoogleDriveFile(googleFile)
    val expectedProperties = GoogleDriveJsonProtocol.WritableFile(name = None, mimeType = None, parents = None,
      createdTime = None, modifiedTime = Some(srcFile.lastModifiedAt), description = srcFile.description,
      properties = googleFile.properties, appProperties = googleFile.appProperties)
    stubFor(patch(urlPathEqualTo(filePath(TestFileID)))
      .willReturn(aJsonResponse(StatusCodes.Created).withBodyFile("resolveFolderResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateFile(srcFile)))
    verify(patchRequestedFor(urlPathEqualTo(filePath(TestFileID)))
      .withHeader(HeaderContent, equalTo(ContentJson))
      .withRequestBody(equalToFile(expectedProperties)))
  }

  it should "patch a model folder without updates" in {
    val folder = modelFolder(fid = "folderID", fName = "folderName", fDesc = Some("folderDesc"),
      fCreate = Instant.parse("2021-08-28T19:34:30.369Z"),
      fModify = Instant.parse("2021-08-28T19:34:59.741Z"))
    val expGoogleFile = GoogleDriveJsonProtocol.File(id = folder.id, name = folder.name, parents = null,
      mimeType = null, createdTime = folder.createdAt, modifiedTime = folder.lastModifiedAt,
      description = folder.description, size = None, properties = None, appProperties = None)
    val expResultFolder = GoogleDriveModel.GoogleDriveFolder(expGoogleFile)
    val fs = new GoogleDriveFileSystem(createConfig())

    fs.patchFolder(folder, ElementPatchSpec()) should be(expResultFolder)
  }

  it should "patch a model file without updates" in {
    val file = modelFile(fid = "fileID", fName = "MyFile.txt", fSize = 16385,
      fCreate = Instant.parse("2021-08-29T18:22:48.528Z"),
      fModify = Instant.parse("2021-08-29T18:23:20.741Z"))
    val expGoogleFile = GoogleDriveJsonProtocol.File(id = file.id, name = file.name, parents = null,
      mimeType = null, createdTime = file.createdAt, modifiedTime = file.lastModifiedAt, description = None,
      size = Some("16385"), properties = None, appProperties = None)
    val expResultFile = GoogleDriveModel.GoogleDriveFile(expGoogleFile)
    val fs = new GoogleDriveFileSystem(createConfig())

    fs.patchFile(file, ElementPatchSpec()) should be(expResultFile)
  }

  it should "patch a Google element without updates" in {
    val googleFile = GoogleDriveJsonProtocol.File(id = "123456", name = "theGoogleName", size = None,
      mimeType = "application/vnd.google-apps.folder", parents = List("parentID"),
      createdTime = Instant.parse("2021-08-29T18:48:14.774Z"),
      modifiedTime = Instant.parse("2021-08-29T18:48:40.332Z"), description = Some("description"),
      properties = Some(Map("foo" -> "bar")), appProperties = Some(Map("appFoo" -> "appBar")))
    val source = GoogleDriveModel.GoogleDriveFolder(googleFile)
    val fs = new GoogleDriveFileSystem(createConfig())

    val patchResult = fs.patchFolder(source, ElementPatchSpec())
    patchResult.googleFile should be(googleFile)
  }

  it should "patch an element with updates" in {
    val NewName = "thePatchedName"
    val NewSize = 32769
    val spec = ElementPatchSpec(patchName = Some(NewName), patchSize = Some(NewSize))
    val file = modelFile(fid = "fileID", fName = "MyOriginalFile.txt", fSize = 16385,
      fCreate = Instant.parse("2021-08-29T18:58:54.528Z"),
      fModify = Instant.parse("2021-08-29T18:59:03.741Z"))
    val expGoogleFile = GoogleDriveJsonProtocol.File(id = file.id, name = NewName, parents = null,
      mimeType = null, createdTime = file.createdAt, modifiedTime = file.lastModifiedAt, description = None,
      size = Some(NewSize.toString), properties = None, appProperties = None)
    val expResultFile = GoogleDriveModel.GoogleDriveFile(expGoogleFile)
    val fs = new GoogleDriveFileSystem(createConfig())

    fs.patchFile(file, spec) should be(expResultFile)
  }

  it should "take the timeout from the configuration into account" in {
    val config = createConfig(timeout = Timeout(250.millis))
    stubFor(get(anyUrl())
      .willReturn(aJsonResponse().withBodyFile("resolveFileResponse.json")
        .withFixedDelay(1000)))
    val fs = new GoogleDriveFileSystem(config)

    expectFailedFuture[TimeoutException](runOp(fs.resolveFile(TestFileID)))
  }

  it should "resolve a path inside the root folder" in {
    val FileName = "SearchedFile.txt"
    val response = resolveResponse(generateResolvedIDs(TestFileID, "anotherID", "oneMoreID"))
    stubResolveRequest("root", FileName, response)
    val fs = new GoogleDriveFileSystem(createConfig())

    val id = futureResult(runOp(fs.resolvePath(FileName)))
    id should be(TestFileID)
  }

  it should "handle a path in the root folder that cannot be resolved" in {
    val FileName = "nonExistingFile.dat"
    stubFailedResolveRequest("root", FileName)
    val fs = new GoogleDriveFileSystem(createConfig())

    val exception = expectFailedFuture[IllegalArgumentException](runOp(fs.resolvePath(FileName)))
    exception.getMessage should include(FileName)
  }

  it should "resolve a path with multiple components" in {
    val Path = "my/test/file.txt"
    stubResolveRequest("root", "my", resolveResponse(generateResolvedIDs("id1", "id_other")),
      foldersOnly = true)
    stubResolveRequest("id1", "test", resolveResponse(generateResolvedIDs("id2")),
      foldersOnly = true)
    stubResolveRequest("id2", "file.txt", resolveResponse(generateResolvedIDs(TestFileID)))
    val fs = new GoogleDriveFileSystem(createConfig())

    val id = futureResult(runOp(fs.resolvePath(Path)))
    id should be(TestFileID)
  }

  it should "correctly resolve an empty path" in {
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.resolvePath(""))) should be("root")
    futureResult(runOp(fs.resolvePathComponents(Seq.empty))) should be("root")
  }

  it should "try different paths in a resolve operation" in {
    val Path = "my/very%20complex/path"
    stubResolveRequest("root", "my", resolveResponse(generateResolvedIDs("w1", "id1")),
      foldersOnly = true)
    stubFailedResolveRequest("w1", "very complex", foldersOnly = true)
    stubResolveRequest("id1", "very complex", resolveResponse(generateResolvedIDs("w2", "id2", "w3")),
      foldersOnly = true)
    stubFailedResolveRequest("w2", "path")
    stubResolveRequest("id2", "path", resolveResponse(generateResolvedIDs(TestFileID)))
    val fs = new GoogleDriveFileSystem(createConfig())

    val id = futureResult(runOp(fs.resolvePath(Path)))
    id should be(TestFileID)
  }

  it should "deal with paging in a resolve operation" in {
    val Dir = "the"
    val File = "file.txt"
    val Path = s"$Dir/$File"
    val Token1 = "page1"
    val Token2 = "page2"
    stubResolveRequest("root", Dir,
      resolveResponse(generateResolvedIDs("id1"), optNextToken = Some(Token1)), foldersOnly = true)
    stubResolveRequest("root", Dir,
      resolveResponse(generateResolvedIDs("id2"), optNextToken = Some(Token2)),
      optPageToken = Some(Token1), foldersOnly = true)
    stubResolveRequest("root", Dir,
      resolveResponse(generateResolvedIDs("id3")), optPageToken = Some(Token2), foldersOnly = true)
    stubFailedResolveRequest("id1", File)
    stubFailedResolveRequest("id2", File)
    stubResolveRequest("id3", File, resolveResponse(generateResolvedIDs(TestFileID)))
    val fs = new GoogleDriveFileSystem(createConfig())

    val id = futureResult(runOp(fs.resolvePath(Path)))
    id should be(TestFileID)
  }

  it should "resolve the root path if it is specified" in {
    val RootPath = "my/root"
    stubResolveRequest("root", "my", resolveResponse(generateResolvedIDs("id1")),
      foldersOnly = true)
    stubResolveRequest("id1", "root", resolveResponse(generateResolvedIDs(TestFileID)))
    val fs = new GoogleDriveFileSystem(createConfig(optRoot = Some(RootPath)))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(TestFileID)
  }

  it should "resolve the root path if it starts with a slash" in {
    val RootFolder = "data"
    stubResolveRequest("root", RootFolder, resolveResponse(generateResolvedIDs(TestFileID)))
    val fs = new GoogleDriveFileSystem(createConfig(optRoot = Some("/" + RootFolder)))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(TestFileID)
  }

  it should "take the root path into account when resolving a path" in {
    stubResolveRequest("root", "the", resolveResponse(generateResolvedIDs("id1")),
      foldersOnly = true)
    stubResolveRequest("id1", "root path", resolveResponse(generateResolvedIDs("id2")),
      foldersOnly = true)
    stubResolveRequest("id2", "data", resolveResponse(generateResolvedIDs(TestFileID)))
    val fs = new GoogleDriveFileSystem(createConfig(optRoot = Some("the/root%20path")))

    val id = futureResult(runOp(fs.resolvePath("/data")))
    id should be(TestFileID)
  }

  it should "query the content of a folder if trashed files should be included" in {
    val queryParams = ExpFolderQueryParams + ("q" -> equalTo(s"'$TestFileID' in parents"))
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParams(toQueryParams(queryParams))
      .willReturn(aJsonResponse().withBodyFile("folderContentResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig(includeTrashed = true))

    val content = futureResult(runOp(fs.folderContent(TestFileID)))
    checkFolderContent(content, expFileCount = 3, expFolderCount = 2)
  }

  it should "query the content of a folder, allowing to override the includeTrashed flag" in {
    stubFor(get(urlPathEqualTo(DriveApiPrefix))
      .withHeader(HeaderAccept, equalTo(ContentJson))
      .withQueryParams(toQueryParams(ExpFolderQueryParams))
      .willReturn(aJsonResponse().withBodyFile("folderContentResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig(includeTrashed = true))

    val content = futureResult(runOp(fs.folderContent(TestFileID, includeTrashed = false)))
    checkFolderContent(content, expFileCount = 3, expFolderCount = 2)
  }

  it should "resolve a path if trashed files should be included" in {
    stubResolveRequest("root", "my", resolveResponse(generateResolvedIDs("id1", "id_other")),
      foldersOnly = true, includeTrashed = true)
    stubResolveRequest("id1", "test", resolveResponse(generateResolvedIDs("id2")),
      foldersOnly = true, includeTrashed = true)
    stubResolveRequest("id2", "file.txt", resolveResponse(generateResolvedIDs(TestFileID)),
      includeTrashed = true)
    val fs = new GoogleDriveFileSystem(createConfig(includeTrashed = true))

    val id = futureResult(runOp(fs.resolvePathComponents(List("my", "test", "file.txt"))))
    id should be(TestFileID)
  }

  it should "resolve a path, allowing to override the includeTrashed flag" in {
    val FileName = "SearchedFile.txt"
    val response = resolveResponse(generateResolvedIDs(TestFileID, "anotherID", "oneMoreID"))
    stubResolveRequest("root", FileName, response)
    val fs = new GoogleDriveFileSystem(createConfig(includeTrashed = true))

    val id = futureResult(runOp(fs.resolvePath(FileName, includeTrashed = false)))
    id should be(TestFileID)
  }

  it should "update an element based on a WritableFile" in {
    val updateSpec = GoogleDriveJsonProtocol.WritableFile(name = None, mimeType = None, parents = None,
      createdTime = None, modifiedTime = Some(Instant.parse("2021-08-28T19:05:55.347Z")),
      description = Some("new description"),
      properties = Some(Map("newProperty" -> "newValue")),
      appProperties = Some(Map("newAppProperty" -> "anotherNewValue")))
    stubFor(patch(urlPathEqualTo(filePath(TestFileID)))
      .willReturn(aJsonResponse(StatusCodes.Created).withBodyFile("resolveFileResponse.json")))
    val fs = new GoogleDriveFileSystem(createConfig())

    futureResult(runOp(fs.updateElement(TestFileID, updateSpec)))
    verify(patchRequestedFor(urlPathEqualTo(filePath(TestFileID)))
      .withHeader(HeaderContent, equalTo(ContentJson))
      .withRequestBody(equalToFile(updateSpec)))
  }
}
