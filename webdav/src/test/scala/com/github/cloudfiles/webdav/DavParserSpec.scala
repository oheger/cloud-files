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

package com.github.cloudfiles.webdav

import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper}
import com.github.cloudfiles.webdav.DavModel.AttributeKey
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.xml.sax.SAXParseException

import java.time.Instant

object DavParserSpec {
  /** The standard namespace for DAV. */
  private val NS_DAV = "DAV:"

  /** The namespace to indicate additional windows attributes. */
  private val NS_MICROSOFT = "urn:schemas-microsoft-com:"

  /**
   * Helper function to convert a string to an instant.
   *
   * @param str the string form of the instant
   * @return the instant
   */
  private def toInstant(str: String): Instant =
    Instant.parse(str)
}

/**
 * Test class for ''DavParser''.
 */
class DavParserSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with AsyncTestHelper
  with FileTestHelper {

  import DavParserSpec._

  "DavParser" should "extract sub folders from a folder content" in {
    val testResponse = resourceFile("/__files/folder.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    val result = futureResult(parser.parseFolderContent(source))
    result.folderID should be(Uri("/test/folder1/"))
    val subFolderUri = Uri("/test%20data/subFolder%20%281%29/")
    result.folders.keys should contain only subFolderUri
    val folder = result.folders(subFolderUri)
    folder.id should be(subFolderUri)
    folder.description should be(None)
    folder.createdAt should be(toInstant("2018-08-27T18:38:25Z"))
    folder.lastModifiedAt should be(toInstant("2018-08-28T19:39:26Z"))
    folder.name should be("subFolder (1)")
    folder.attributes.values(AttributeKey(NS_DAV, "getcontentlanguage")) should be("de")
    folder.attributes.values(AttributeKey(NS_DAV, "getetag")) should be("AAABZYIMQzwAAAFlggunMA")
    folder.attributes.values.keys should not contain AttributeKey(NS_DAV, "displayname")
  }

  it should "extract files from a folder content" in {
    val testResponse = resourceFile("/__files/folder.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    val result = futureResult(parser.parseFolderContent(source))
    val fileUri1 = Uri("/test%20data/folder%20%281%29/file%20%281%29.mp3")
    val fileUri2 = Uri("/test%20data/folder%20%281%29/file%20%282%29.mp3")
    val fileUri3 = Uri("/test%20data/folder%20%281%29/file%20%283%29.mp3")
    result.files.keys should contain only(fileUri1, fileUri2, fileUri3)
    val file1 = result.files(fileUri1)
    file1.id should be(fileUri1)
    file1.name should be("file1.mp3")
    file1.createdAt should be(toInstant("2018-09-19T20:08:00Z"))
    file1.size should be(100)
    val file3 = result.files(fileUri3)
    file3.name should be("file3.mp3")
    file3.size should be(300)
    file3.attributes.values(AttributeKey(NS_MICROSOFT,
      "Win32LastModifiedTime")) should be("Wed, 19 Sep 2018 20:12:00 GMT")
  }

  it should "add slashes to folder URIs if necessary" in {
    val testResponse = resourceFile("/__files/folder_no_slashes.xml")
    val source = FileIO.fromPath(testResponse)
    val subFolder1Uri = Uri("/test%20data/subFolder%20%281%29/")
    val subFolder2Uri = Uri("/test%20data/subFolder2/")
    val parser = new DavParser()

    val result = futureResult(parser.parseFolderContent(source))
    result.folderID should be(Uri("/test/folder1/"))
    result.folders.keys should contain only(subFolder1Uri, subFolder2Uri)
  }

  it should "extract the content of an empty folder" in {
    val testResponse = resourceFile("/__files/empty_folder.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    val result = futureResult(parser.parseFolderContent(source))
    result.folderID should be(Uri("/destination/"))
    result.files should have size 0
    result.folders should have size 0
  }

  it should "handle invalid date and time values in standard attributes" in {
    val testResponse = resourceFile("/__files/folder_invalid_attributes.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    val result = futureResult(parser.parseFolderContent(source))
    val file = result.files(Uri("/test/folder1/test.txt"))
    file.createdAt should be(DavParser.UndefinedDate)
    file.lastModifiedAt should be(DavParser.UndefinedDate)
    file.size should be(-1)
  }

  it should "deal with missing attributes for elements" in {
    val testResponse = resourceFile("/__files/folder_missing_attributes.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    val result = futureResult(parser.parseFolderContent(source))
    val file = result.files(Uri("/test/folder1/test.txt"))
    file.createdAt should be(DavParser.UndefinedDate)
    file.lastModifiedAt should be(DavParser.UndefinedDate)
    file.size should be(-1)
    file.name should be("test.txt")
  }

  it should "ignore a response element with unexpected content" in {
    val testResponse = resourceFile("/__files/folder_unexpected.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    val result = futureResult(parser.parseFolderContent(source))
    val fileUri = Uri("/test/folder1/music.mp3")
    result.files.keys should contain only fileUri
    val file = result.files(fileUri)
    file.name should be("music.mp3")
  }

  it should "extract a description if the property is defined" in {
    val KeyDesc = AttributeKey("urn:test-org", "testDesc")
    val testResponse = resourceFile("/__files/folder.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser(optDescriptionKey = Some(KeyDesc))

    val result = futureResult(parser.parseFolderContent(source))
    val file = result.files(Uri("/test%20data/folder%20%281%29/file%20%283%29.mp3"))
    file.description should be(Some("A test description"))
    file.attributes.values.keys should not contain KeyDesc
  }

  it should "handle a non-XML response" in {
    val content = ByteString("This is not an XML document!?")
    val source = Source.single(content)
    val parser = new DavParser()

    expectFailedFuture[SAXParseException](parser.parseFolderContent(source))
  }

  it should "handle multiple propstat elements per element" in {
    val testResponse = resourceFile("/__files/folder_with_failure_props.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser

    val result = futureResult(parser.parseFolderContent(source))
    val file1 = result.files(Uri("/test%20data/folder%20%281%29/file%20%281%29.mp3"))
    file1.name should be("testFile")
    val file2 = result.files(Uri("/test%20data/folder%20%281%29/file%20%282%29.mp3"))
    file2.name should be("testFile2")
    val folder = result.folders(Uri("/test%20data/subFolder%20%281%29/"))
    folder.attributes.values(AttributeKey(NS_MICROSOFT,
      "Win32LastModifiedTime")) should be("Tue, 8 Mar 2022 20:35:10 GMT")
  }

  it should "parse a folder element" in {
    val testResponse = resourceFile("/__files/empty_folder.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    futureResult(parser.parseElement(source)) match {
      case folder: DavModel.DavFolder =>
        folder.name should be("test")
        folder.lastModifiedAt should be(toInstant("2018-08-30T20:07:40Z"))
        folder.attributes.values(DavModel.AttributeKey(NS_DAV, "getcontenttype")) should be("httpd/unix-directory")
      case r =>
        fail("Unexpected result: " + r)
    }
  }

  it should "parse a file element" in {
    val KeyDesc = AttributeKey("urn:test-org", "testDesc")
    val testResponse = resourceFile("/__files/element_file.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser(optDescriptionKey = Some(KeyDesc))

    futureResult(parser.parseElement(source)) match {
      case file: DavModel.DavFile =>
        file.name should be("test.txt")
        file.lastModifiedAt should be(toInstant("2020-12-31T19:23:52Z"))
        file.description should be(Some("A test description"))
      case r =>
        fail("Unexpected result: " + r)
    }
  }

  it should "handle an unexpected response for an element" in {
    val testResponse = resourceFile("/__files/element_unexpected.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser()

    expectFailedFuture[Throwable](parser.parseElement(source))
  }

  it should "parse a successful multi-status response" in {
    val testResponse = resourceFile("/__files/multi_status_success.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser

    futureResult(parser.parseMultiStatus(source))
  }

  it should "parse a failed multi-status response" in {
    val testResponse = resourceFile("/__files/multi_status_failed.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser

    val exception = expectFailedFuture[FailedResponseException](parser.parseMultiStatus(source))
    exception.response.status should be(StatusCodes.Conflict)
  }

  it should "handle an invalid status code in a multi-status response" in {
    val testResponse = resourceFile("/__files/multi_status_invalid.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser

    val exception = expectFailedFuture[IllegalStateException](parser.parseMultiStatus(source))
    exception.getMessage should include("an_invalid_response_code")
  }

  it should "handle an empty multi-status response" in {
    val testResponse = resourceFile("/__files/multi_status_empty.xml")
    val source = FileIO.fromPath(testResponse)
    val parser = new DavParser

    futureResult(parser.parseMultiStatus(source))
  }

  it should "handle a non XML multi-status response" in {
    val source = Source.single(ByteString("This is not an XML response"))
    val parser = new DavParser

    expectFailedFuture[SAXParseException](parser.parseMultiStatus(source))
  }
}
