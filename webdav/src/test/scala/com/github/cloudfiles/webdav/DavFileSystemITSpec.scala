/*
 * Copyright 2020-2025 The Developers Team.
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

package com.github.cloudfiles.webdav

import com.github.cloudfiles.core.Model.Folder
import com.github.cloudfiles.core._
import com.github.cloudfiles.core.delegate.ElementPatchSpec
import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}
import com.github.cloudfiles.webdav.DavModel.AttributeKey
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.{AbsentPattern, ContentPattern}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}
import org.mockito.Mockito.when
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{Future, TimeoutException}

object DavFileSystemITSpec {
  /** The root path for all server requests. */
  private val RootPath = "/dav/data"

  /** A namespace for test properties */
  private val NS_TEST = "urn:test-org"

  /** A custom property holding the element description. */
  private val AttrDescription = DavModel.AttributeKey(NS_TEST, "testDesc")

  /** The size of the content of test files. */
  private val FileContentSize = FileTestHelper.testBytes().length.toLong

  /**
   * Adds a stubbing declaration for a request to a folder that is served with
   * the file specified.
   *
   * @param uri          the URI of the folder
   * @param responseFile the file to serve the request
   * @param status       the status code to return from the request
   * @param depth        the value for the Depth header
   * @param optDelay     an optional delay for this request
   * @param withSlash    flag whether a slash should be added to the URI
   * @param expBody      an optional expected body in the request
   */
  private def stubFolderRequest(uri: String, responseFile: String,
                                status: Int = StatusCodes.OK.intValue,
                                depth: String = "1",
                                optDelay: Option[FiniteDuration] = None,
                                withSlash: Boolean = true,
                                expBody: Option[String] = None): Unit = {
    val delay = optDelay.map(_.toMillis.toInt).getOrElse(0)
    val requestUri = if (withSlash) UriEncodingHelper withTrailingSeparator uri
    else uri
    val bodyPattern = expBody.fold(AbsentPattern.ABSENT: ContentPattern[String])(body => equalTo(body))
    stubFor(request("PROPFIND", urlPathEqualTo(requestUri))
      .withHeader("Accept", equalTo("text/xml"))
      .withHeader("Depth", equalTo(depth))
      .withRequestBody(bodyPattern)
      .willReturn(aResponse()
        .withStatus(status)
        .withFixedDelay(delay)
        .withBodyFile(responseFile)))
  }

  /**
   * Returns a source which emits well-known test data in chunks.
   *
   * @return the source for the content of a test file
   */
  private def fileContentSource: Source[ByteString, Any] =
    Source(FileTestHelper.testBytes().grouped(64).map(data => ByteString(data)).toList)
}

/**
 * Integration test class for ''DavFileSystem''.
 */
class DavFileSystemITSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers with MockitoSugar
  with WireMockSupport with FileTestHelper {
  override protected val resourceRoot: String = "webdav"

  import DavFileSystemITSpec._

  /**
   * Returns a ''DavConfig'' object with default settings for interacting with
   * the test server.
   *
   * @return the test ''DavConfig''
   */
  private def createConfig(): DavConfig =
    DavConfig(rootUri = serverUri(RootPath), timeout = Timeout(3.seconds))

  /**
   * Executes the given operation using a new request sender actor.
   *
   * @param op the ''FileSystem'' operation
   * @tparam A the result type of the operation
   * @return a ''Future'' with the result of the operation
   */
  private def runOp[A](op: FileSystem.Operation[A]): Future[A] = {
    val requestSender = testKit.spawn(HttpRequestSender(serverUri("/")))
    op.run(requestSender)
  }

  "DavFileSystem" should "resolve a path" in {
    val TestPath = "/test/files/data.txt"
    val ExpUri = Uri(serverUri(RootPath + TestPath))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.resolvePath(TestPath)) map { uri =>
      uri should be(ExpUri)
    }
  }

  it should "resolve a path not starting with a slash" in {
    val TestPath = "no/leading/slash.txt"
    val ExpUri = Uri(serverUri(RootPath + "/" + TestPath))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.resolvePath(TestPath)) map { uri =>
      uri should be(ExpUri)
    }
  }

  it should "return the correct root ID" in {
    val config = createConfig()
    val fs = new DavFileSystem(config)

    runOp(fs.rootID) map { rootUri =>
      rootUri should be(config.rootUri)
    }
  }

  /**
   * Checks whether the content of a folder can be queried correctly.
   *
   * @param folderUri the URI of the folder
   * @return the ''Future'' with the test assertion
   */
  private def checkFolderContent(folderUri: Uri): Future[Assertion] = {
    stubFolderRequest(folderUri.path.toString(), "folder.xml")
    val fs = new DavFileSystem(createConfig())

    runOp(fs.folderContent(folderUri)) map { result =>
      val subFolderUri = Uri("/test%20data/subFolder%20%281%29/")
      result.folders.keys should contain only subFolderUri
      val folder = result.folders(subFolderUri)
      folder.id should be(subFolderUri)
      folder.name should be("subFolder (1)")
      result.files should have size 3
      val fileUri3 = Uri("/test%20data/folder%20%281%29/file%20%283%29.mp3")
      val file3 = result.files(fileUri3)
      file3.name should be("file3.mp3")
      file3.size should be(300)
      file3.attributes.values(AttributeKey("urn:schemas-microsoft-com:",
        "Win32LastModifiedTime")) should be("Wed, 19 Sep 2018 20:12:00 GMT")
      file3.description shouldBe empty
    }
  }

  it should "return the content of a folder" in {
    checkFolderContent(Uri(RootPath + "/test/"))
  }

  it should "add a trailing slash when querying the content of a folder" in {
    checkFolderContent(Uri(RootPath + "/test"))
  }

  it should "query the description of elements from the folder content" in {
    val folderUri = Uri(RootPath + "/test/")
    val expRequest = PropRequestGenerator.generatePropFind(Nil, Some(AttrDescription))
    stubFolderRequest(folderUri.path.toString(), "folder.xml", expBody = expRequest)
    val config = createConfig().copy(optDescriptionKey = Some(AttrDescription))
    val fs = new DavFileSystem(config)

    runOp(fs.folderContent(folderUri)) map { result =>
      val fileUri3 = Uri("/test%20data/folder%20%281%29/file%20%283%29.mp3")
      val file3 = result.files(fileUri3)
      file3.description should be(Some("A test description"))
    }
  }

  it should "query additional attributes for elements from the folder content" in {
    val folderUri = Uri(RootPath + "/test/")
    val additionalAttributes = List(DavModel.AttributeKey(NS_TEST, "attr1"), DavModel.AttributeKey(NS_TEST, "attr2"))
    val expRequest = PropRequestGenerator.generatePropFind(additionalAttributes, None)
    stubFolderRequest(folderUri.path.toString(), "folder.xml", expBody = expRequest)
    val config = createConfig().copy(additionalAttributes = additionalAttributes)
    val fs = new DavFileSystem(config)

    runOp(fs.folderContent(folderUri)) map { result =>
      result.folders should have size 1
      result.files should have size 3
    }
  }

  it should "handle a failed request for the content of a folder" in {
    stubFor(request("PROPFIND", anyUrl())
      .willReturn(aResponse().withStatus(StatusCodes.NotFound.intValue)))
    val fs = new DavFileSystem(createConfig())

    recoverToExceptionIf[FailedResponseException](runOp(fs.folderContent("/some/uri"))) map { exception =>
      exception.response.status should be(StatusCodes.NotFound)
    }
  }

  it should "resolve a file by its ID" in {
    val FileUri = Uri(RootPath + "/sub/data.dat")
    stubFolderRequest(FileUri.path.toString(), "element_file.xml", depth = "0", withSlash = false)
    val fs = new DavFileSystem(createConfig())

    runOp(fs.resolveFile(FileUri)) map { file =>
      file.name should be("test.txt")
      file.lastModifiedAt should be(Instant.parse("2020-12-31T19:23:52Z"))
      file.description shouldBe empty
    }
  }

  it should "take additional attributes into account when resolving a file by its ID" in {
    val FileUri = Uri(RootPath + "/sub/data.dat")
    val additionalAttributes = List(DavModel.AttributeKey(NS_TEST, "additional"))
    val expRequest = PropRequestGenerator.generatePropFind(additionalAttributes, Some(AttrDescription))
    stubFolderRequest(FileUri.path.toString(), "element_file.xml", depth = "0", withSlash = false,
      expBody = expRequest)
    val config = createConfig().copy(optDescriptionKey = Some(AttrDescription),
      additionalAttributes = additionalAttributes)
    val fs = new DavFileSystem(config)

    runOp(fs.resolveFile(FileUri)) map { file =>
      file.name should be("test.txt")
      file.lastModifiedAt should be(Instant.parse("2020-12-31T19:23:52Z"))
      file.description should be(Some("A test description"))
    }
  }

  it should "handle a request to resolve a file that yields a folder" in {
    val FileUri = Uri(RootPath + "/sub/")
    stubFolderRequest(FileUri.path.toString(), "empty_folder.xml", depth = "0")
    val fs = new DavFileSystem(createConfig())

    recoverToExceptionIf[IllegalArgumentException](runOp(fs.resolveFile(FileUri))) map { exception =>
      exception.getMessage should include(FileUri.toString())
    }
  }

  /**
   * Checks whether a folder can be resolved by its URI.
   *
   * @param folderUri the folder URI
   * @return the ''Future'' with the test assertion
   */
  private def checkResolveFolder(folderUri: Uri): Future[Assertion] = {
    stubFolderRequest(folderUri.path.toString(), "empty_folder.xml", depth = "0")
    val fs = new DavFileSystem(createConfig())

    runOp(fs.resolveFolder(folderUri)) map { folder =>
      folder.name should be("test")
      folder.lastModifiedAt should be(Instant.parse("2018-08-30T20:07:40Z"))
      folder.attributes.values(DavModel.AttributeKey("DAV:", "getcontenttype")) should be("httpd/unix-directory")
    }
  }

  it should "resolve a folder by its ID" in {
    checkResolveFolder(Uri(RootPath + "/sub/folder/"))
  }

  it should "resolve a folder by its ID if it misses the trailing slash" in {
    checkResolveFolder(Uri(RootPath + "/sub/folder"))
  }

  it should "handle a request to resolve a folder that yields a file" in {
    val FolderUri = Uri(RootPath + "/sub/folder/aFile.jpg/")
    stubFolderRequest(FolderUri.path.toString(), "element_file.xml", depth = "0")
    val fs = new DavFileSystem(createConfig())

    recoverToExceptionIf[IllegalArgumentException](runOp(fs.resolveFolder(FolderUri))) map { exception =>
      exception.getMessage should include(FolderUri.toString())
    }
  }

  it should "delete a folder" in {
    val FolderURI = Uri(RootPath + "/folder/to/delete")
    stubSuccess(WireMockSupport.NoAuthFunc)
    val fs = new DavFileSystem(createConfig())

    runOp(fs.deleteFolder(FolderURI)) map { _ =>
      verify(deleteRequestedFor(urlPathEqualTo(FolderURI.path.toString() + "/")))
      succeed
    }
  }

  it should "delete a file" in {
    val FileURI = Uri(RootPath + "/file/to/delete.dat")
    stubSuccess(WireMockSupport.NoAuthFunc)
    val fs = new DavFileSystem(createConfig())

    runOp(fs.deleteFile(FileURI)) map { _ =>
      verify(deleteRequestedFor(urlPathEqualTo(FileURI.path.toString())))
      succeed
    }
  }

  it should "discard the entities of requests where the response does not matter" in {
    stubSuccess(WireMockSupport.NoAuthFunc)
    val fs = new DavFileSystem(createConfig())

    // Execute a number of operations. If the entities are not discarded, the
    // HTTP pipeline will block, and we will run into a timeout.
    val futResults = (1 to 16) map { idx =>
      val uri = Uri(RootPath + s"/files/file$idx.dat")
      runOp(fs.deleteFile(uri))
    }
    Future.sequence(futResults) map { _ => succeed }
  }

  it should "download a file" in {
    val FileUri = Uri(RootPath + "/data/testFile.txt")
    stubFor(get(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(FileTestHelper.TestData)))
    val fs = new DavFileSystem(createConfig())

    val futResult = runOp(fs.downloadFile(FileUri)) flatMap { entity =>
      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      entity.dataBytes.runWith(sink)
    }
    futResult map { result =>
      result.utf8String should be(FileTestHelper.TestData)
    }
  }

  it should "create a folder without additional attributes" in {
    val folder = mock[Folder[Uri]]
    val FolderName = "child"
    val ParentUri = Uri(RootPath + "/parent")
    val FolderUri = ParentUri.withPath(ParentUri.path / FolderName)
    when(folder.name).thenReturn(FolderName)
    when(folder.description).thenReturn(None)
    stubFor(request("MKCOL", urlPathEqualTo(FolderUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.Created.intValue)))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.createFolder(ParentUri, folder)) map { result =>
      result.path should be(FolderUri.path)
      getAllServeEvents should have size 1
    }
  }

  it should "create a folder with additional attributes" in {
    val FolderName = "folderWithAttributes"
    val ParentUri = Uri(RootPath + "/parent")
    val FolderUri = ParentUri.withPath(ParentUri.path / FolderName)
    val keyAdd = DavModel.AttributeKey(NS_TEST, "foo")
    val attributes = DavModel.Attributes(Map(keyAdd -> "<foo> value"))
    val expPatch = readDataFile(resourceFile("/proppatch_attributes.xml"))
    val newFolder = DavModel.newFolder(name = FolderName, description = Some("<cool> description ;-)"),
      attributes = attributes)
    stubFor(request("MKCOL", urlPathEqualTo(FolderUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.Created.intValue)))
    stubFor(request("PROPPATCH", urlPathEqualTo(FolderUri.path.toString() + "/"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val config = createConfig().copy(optDescriptionKey = Some(AttrDescription))
    val fs = new DavFileSystem(config)

    runOp(fs.createFolder(ParentUri, newFolder)) map { result =>
      verify(anyRequestedFor(urlPathEqualTo(FolderUri.path.toString() + "/"))
        .withHeader("Content-Type", equalTo("text/xml; charset=UTF-8"))
        .withRequestBody(equalToXml(expPatch)))
      result.path should be(FolderUri.path)
    }
  }

  it should "create a folder if the parent URI ends on a slash" in {
    val folder = mock[Folder[Uri]]
    val FolderName = "child"
    val ParentUriStr = RootPath + "/parent"
    val ParentUri = Uri(ParentUriStr)
    val FolderUri = ParentUri.withPath(ParentUri.path / FolderName)
    when(folder.name).thenReturn(FolderName)
    when(folder.description).thenReturn(None)
    stubFor(request("MKCOL", urlPathEqualTo(FolderUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.Created.intValue)))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.createFolder(Uri(ParentUriStr + "/"), folder)) map { result =>
      result.path should be(FolderUri.path)
      getAllServeEvents should have size 1
    }
  }

  it should "update a folder without additional attributes" in {
    val folder = mock[Folder[Uri]]
    when(folder.id).thenReturn(Uri(RootPath + "/some/uri"))
    when(folder.description).thenReturn(None)
    val fs = new DavFileSystem(createConfig())

    runOp(fs.updateFolder(folder)) map { _ =>
      getAllServeEvents should have size 0
    }
  }

  it should "update a folder's attributes" in {
    val FolderUri = Uri(RootPath + "/folder/to/update")
    val keyAdd = DavModel.AttributeKey(NS_TEST, "foo")
    val keyDel = DavModel.AttributeKey(NS_TEST, "del")
    val attributes = DavModel.Attributes(Map(keyAdd -> "<foo> value"), List(keyDel))
    val expPatch = readDataFile(resourceFile("/proppatch_attributes_remove.xml"))
    val folder = DavModel.DavFolder(id = FolderUri, lastModifiedAt = null, createdAt = null,
      name = "ignore", description = Some("<cool> description ;-)"), attributes = attributes)
    stubFor(request("PROPPATCH", urlPathEqualTo(FolderUri.path.toString() + "/"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val config = createConfig().copy(optDescriptionKey = Some(AttrDescription))
    val fs = new DavFileSystem(config)

    runOp(fs.updateFolder(folder)) map { _ =>
      verify(anyRequestedFor(urlPathEqualTo(FolderUri.path.toString() + "/"))
        .withHeader("Content-Type", equalTo("text/xml; charset=UTF-8"))
        .withRequestBody(equalToXml(expPatch)))
      succeed
    }
  }

  it should "update a file without additional attributes" in {
    val file = mock[Model.File[Uri]]
    when(file.description).thenReturn(None)
    val fs = new DavFileSystem(createConfig())

    runOp(fs.updateFile(file)) map { _ =>
      getAllServeEvents should have size 0
    }
  }

  it should "update a file's attributes" in {
    val FileUri = Uri(RootPath + "/file/to/update.txt")
    val keyAdd = DavModel.AttributeKey(NS_TEST, "foo")
    val keyDel = DavModel.AttributeKey(NS_TEST, "del")
    val attributes = DavModel.Attributes(Map(keyAdd -> "<foo> value"), List(keyDel))
    val expPatch = readDataFile(resourceFile("/proppatch_attributes_remove.xml"))
    val file = DavModel.DavFile(id = FileUri, lastModifiedAt = null, createdAt = null,
      name = "ignore", description = Some("<cool> description ;-)"), attributes = attributes, size = 0)
    stubFor(request("PROPPATCH", urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val config = createConfig().copy(optDescriptionKey = Some(AttrDescription))
    val fs = new DavFileSystem(config)

    runOp(fs.updateFile(file)) map { _ =>
      verify(anyRequestedFor(urlPathEqualTo(FileUri.path.toString()))
        .withHeader("Content-Type", equalTo("text/xml; charset=UTF-8"))
        .withRequestBody(equalToXml(expPatch)))
      succeed
    }
  }

  it should "update the content of a file" in {
    val FileUri = Uri(RootPath + "/file/to/update.txt")
    stubFor(put(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.updateFileContent(FileUri, FileContentSize, fileContentSource)) map { _ =>
      verify(putRequestedFor(urlPathEqualTo(FileUri.path.toString()))
        .withHeader("Content-Length", equalTo(FileContentSize.toString))
        .withRequestBody(binaryEqualTo(FileTestHelper.testBytes())))
      getAllServeEvents should have size 1
    }
  }

  it should "update the content of the file with deleting it before" in {
    val FileUri = Uri(RootPath + "/file/to/deleteAndRecreate.txt")
    stubFor(delete(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(put(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val fs = new DavFileSystem(createConfig().copy(deleteBeforeOverride = true))

    runOp(fs.updateFileContent(FileUri, FileContentSize, fileContentSource)) map { _ =>
      verify(deleteRequestedFor(urlPathEqualTo(FileUri.path.toString())))
      verify(putRequestedFor(urlPathEqualTo(FileUri.path.toString()))
        .withHeader("Content-Length", equalTo(FileContentSize.toString))
        .withRequestBody(binaryEqualTo(FileTestHelper.testBytes())))
      succeed
    }
  }

  it should "create a file without additional attributes" in {
    val file = mock[Model.File[Uri]]
    val FileName = "importantData.txt"
    val ParentUri = Uri(RootPath + "/parent/folder")
    val FileUri = ParentUri.withPath(ParentUri.path / FileName)
    when(file.name).thenReturn(FileName)
    when(file.size).thenReturn(FileContentSize)
    when(file.description).thenReturn(None)
    stubFor(put(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.createFile(ParentUri, file, fileContentSource)) map { resultUri =>
      resultUri should be(FileUri)
      verify(putRequestedFor(urlPathEqualTo(FileUri.path.toString()))
        .withHeader("Content-Length", equalTo(FileContentSize.toString))
        .withRequestBody(binaryEqualTo(FileTestHelper.testBytes())))
      getAllServeEvents should have size 1
    }
  }

  it should "create a file with additional attributes" in {
    val FileName = "fileWithAttributes.dat"
    val ParentUri = Uri(RootPath + "/parent")
    val FileUri = ParentUri.withPath(ParentUri.path / FileName)
    val keyAdd = DavModel.AttributeKey(NS_TEST, "foo")
    val attributes = DavModel.Attributes(Map(keyAdd -> "<foo> value"))
    val expPatch = readDataFile(resourceFile("/proppatch_attributes.xml"))
    val newFile = DavModel.newFile(name = FileName, description = Some("<cool> description ;-)"),
      attributes = attributes, size = FileContentSize)
    stubFor(put(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val config = createConfig().copy(optDescriptionKey = Some(AttrDescription))
    val fs = new DavFileSystem(config)

    runOp(fs.createFile(ParentUri, newFile, fileContentSource)) map { resultUri =>
      verify(putRequestedFor(urlPathEqualTo(FileUri.path.toString()))
        .withHeader("Content-Length", equalTo(FileContentSize.toString))
        .withRequestBody(binaryEqualTo(FileTestHelper.testBytes())))
      verify(anyRequestedFor(urlPathEqualTo(FileUri.path.toString()))
        .withHeader("Content-Type", equalTo("text/xml; charset=UTF-8"))
        .withRequestBody(equalToXml(expPatch)))
      resultUri should be(FileUri)
    }
  }

  it should "handle a successful multi-status response when creating a file with additional attributes" in {
    val FileName = "fileWithAttributes.dat"
    val ParentUri = Uri(RootPath + "/parent")
    val FileUri = ParentUri.withPath(ParentUri.path / FileName)
    val keyAdd = DavModel.AttributeKey(NS_TEST, "someAttr")
    val attributes = DavModel.Attributes(Map(keyAdd -> "some value"))
    val newFile = DavModel.newFile(name = FileName, attributes = attributes, size = FileContentSize)
    stubFor(put(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.MultiStatus.intValue)
        .withBodyFile("multi_status_success.xml")))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.createFile(ParentUri, newFile, fileContentSource)) map { resultUri =>
      resultUri should be(FileUri)
    }
  }

  it should "handle a failure multi-status response when creating a file with additional attributes" in {
    val FileName = "fileWithInvalidAttributes.dat"
    val ParentUri = Uri(RootPath + "/parent")
    val FileUri = ParentUri.withPath(ParentUri.path / FileName)
    val keyAdd = DavModel.AttributeKey(NS_TEST, "someStrangeAttr")
    val attributes = DavModel.Attributes(Map(keyAdd -> "some strange and invalid value"))
    val newFile = DavModel.newFile(name = FileName, attributes = attributes, size = FileContentSize)
    stubFor(put(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.MultiStatus.intValue)
        .withBodyFile("multi_status_failed.xml")))
    val fs = new DavFileSystem(createConfig())

    recoverToExceptionIf[FailedResponseException](runOp(fs.createFile(ParentUri, newFile, fileContentSource)))
      .map { ex =>
        ex.response.status should be(StatusCodes.Conflict)
      }
  }

  it should "discard response entities when handling multi-status responses" in {
    val RequestCount = 16
    val ParentUri = Uri(RootPath + "/parent")
    val keyAdd = DavModel.AttributeKey(NS_TEST, "someAttr")
    val attributes = DavModel.Attributes(Map(keyAdd -> "testFile"))
    stubFor(put(anyUrl())
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", anyUrl())
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("folder.xml")))
    val fs = new DavFileSystem(createConfig())

    val futResults = (1 to RequestCount) map { idx =>
      val newFile = DavModel.newFile(name = s"FileName$idx", attributes = attributes, size = FileContentSize)
      runOp(fs.createFile(ParentUri, newFile, fileContentSource))
    }
    Future.sequence(futResults) map { _ => succeed }
  }

  it should "create a file if the parent URI ends on a slash" in {
    val file = mock[Model.File[Uri]]
    val FileName = "importantData.txt"
    val ParentUriStr = RootPath + "/parent/folder"
    val ParentUri = Uri(ParentUriStr)
    val FileUri = ParentUri.withPath(ParentUri.path / FileName)
    when(file.name).thenReturn(FileName)
    when(file.size).thenReturn(FileContentSize)
    when(file.description).thenReturn(None)
    stubFor(put(urlPathEqualTo(FileUri.path.toString()))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val fs = new DavFileSystem(createConfig())

    runOp(fs.createFile(Uri(ParentUriStr + "/"), file, fileContentSource)) map { resultUri =>
      resultUri should be(FileUri)
      verify(putRequestedFor(urlPathEqualTo(FileUri.path.toString()))
        .withHeader("Content-Length", equalTo(FileContentSize.toString))
        .withRequestBody(binaryEqualTo(FileTestHelper.testBytes())))
      getAllServeEvents should have size 1
    }
  }

  it should "apply the timeout from the configuration" in {
    val FileUri = Uri(RootPath + "/file/timeout.ex")
    stubFolderRequest(FileUri.path.toString(), "element_file.xml", depth = "0",
      optDelay = Some(1.second), withSlash = false)
    val fs = new DavFileSystem(createConfig().copy(timeout = Timeout(250.millis)))

    recoverToSucceededIf[TimeoutException](runOp(fs.resolveFile(FileUri)))
  }

  it should "patch a folder against an empty patch spec" in {
    val folder = mock[Folder[Uri]]
    val FolderID = Uri("https://my.test.dav/my/folder")
    val FolderName = "originalFolderName"
    val FolderDesc = Some("original folder description")
    when(folder.id).thenReturn(FolderID)
    when(folder.name).thenReturn(FolderName)
    when(folder.description).thenReturn(FolderDesc)
    val expFolder = DavModel.newFolder(FolderName, description = FolderDesc, id = FolderID)
    val fs = new DavFileSystem(createConfig())

    fs.patchFolder(folder, ElementPatchSpec()) should be(expFolder)
  }

  it should "patch a folder against a defined patch spec" in {
    val attributes = DavModel.Attributes(Map(AttributeKey("foo", "key1") -> "value1",
      AttributeKey("bar", "key2") -> "value2"))
    val folder = DavModel.newFolder("originalName", description = Some("original description"),
      attributes = attributes)
    val PatchedName = "newFolderName"
    val expFolder = DavModel.newFolder(name = PatchedName, description = folder.description, attributes = attributes)
    val spec = ElementPatchSpec(patchName = Some(PatchedName), patchSize = Some(42))
    val fs = new DavFileSystem(createConfig())

    fs.patchFolder(folder, spec) should be(expFolder)
  }

  it should "patch a file against an empty patch spec" in {
    val FileID = Uri("https://my.test.dav/my/file.dat")
    val FileName = "file.dat"
    val FileDesc = Some("This is the description of my test file.")
    val FileSize = 20210213164214L
    val attributes = DavModel.Attributes(Map(AttributeKey("foo", "key1") -> "value1",
      AttributeKey("bar", "key2") -> "value2"))
    val file = DavModel.newFile(FileName, FileSize, description = FileDesc, attributes = attributes, id = FileID)
    val fs = new DavFileSystem(createConfig())

    fs.patchFile(file, ElementPatchSpec()) should be(file)
  }

  it should "patch a file against a defined patch spec" in {
    val FileID = Uri("https://some.uri.org/foo")
    val PatchedName = "newFileName.txt"
    val PatchedSize = 20210213164608L
    val file = mock[Model.File[Uri]]
    when(file.id).thenReturn(FileID)
    when(file.name).thenReturn("original.name")
    when(file.size).thenReturn(11L)
    when(file.description).thenReturn(None)
    val expFile = DavModel.newFile(PatchedName, PatchedSize, id = FileID)
    val spec = ElementPatchSpec(patchName = Some(PatchedName), patchSize = Some(PatchedSize))
    val fs = new DavFileSystem(createConfig())

    fs.patchFile(file, spec) should be(expFile)
  }
}
