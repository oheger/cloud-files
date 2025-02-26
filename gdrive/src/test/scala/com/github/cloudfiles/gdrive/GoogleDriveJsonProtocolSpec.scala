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

package com.github.cloudfiles.gdrive

import com.github.cloudfiles.core.FileTestHelper
import com.github.cloudfiles.gdrive.GoogleDriveJsonProtocol.{FolderResponse, WritableFile}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant

/**
 * Test class for ''GoogleDriveJsonProtocol''.
 */
class GoogleDriveJsonProtocolSpec extends AnyFlatSpec with Matchers with FileTestHelper {
  /**
   * Reads the JSON file with the given name and converts it to a
   * [[FolderResponse]] structure.
   *
   * @param fileName the name of the test file to be loaded
   * @return the ''FolderResponse'' extracted from the file
   */
  private def parseFolderResponse(fileName: String): FolderResponse = {
    val responseJson = readDataFile(resourceFile(fileName))
    val jsonAst = responseJson.parseJson
    jsonAst.convertTo[FolderResponse]
  }

  "GoogleDriveJsonProtocol" should "deserialize a file resource" in {
    val response = parseFolderResponse("/folderResponse.json")
    val expectedProperties = Map("foo" -> "bar", "test" -> "true")
    val expectedAppProperties = Map("appFoo" -> "appBar", "appTest" -> "true")

    response.files should have size 2
    val file = response.files(1)
    file.id should be("file_id-J9die95gBb_123456987")
    file.mimeType should be("text/plain")
    file.name should be("test.txt")
    file.createdTime should be(Instant.parse("2021-08-11T20:08:40.000Z"))
    file.modifiedTime should be(Instant.parse("2021-08-11T20:09:50.000Z"))
    file.parents should contain only "parent_id_OxrHyqtqWLAM7EY"
    file.fileSize should be(323)
    file.fileProperties should be(expectedProperties)
    file.fileAppProperties should be(expectedAppProperties)
    file.description should be(Some("Description of test file."))
    file.trashed shouldBe true
    file.trashedTime should be(Some(Instant.parse("2021-09-10T18:59:57.999Z")))
  }

  it should "deserialize a folder resource" in {
    val response = parseFolderResponse("/folderResponse.json")

    val folder = response.files.head
    folder.id should be("folder_id-AECVBSOxrHyqtqTest")
    folder.mimeType should be("application/vnd.google-apps.folder")
    folder.name should be("test")
    folder.createdTime should be(Instant.parse("2021-08-13T20:16:41.000Z"))
    folder.modifiedTime should be(Instant.parse("2021-08-11T20:03:04.254Z"))
    folder.fileSize should be(0)
    folder.fileProperties should have size 0
    folder.fileAppProperties should have size 0
    folder.description should be(empty)
    folder.trashed shouldBe false
    folder.trashedTime should be(None)
  }

  it should "read the token for the next page from a folder response" in {
    val response = parseFolderResponse("/chunkedFolderResponse.json")

    response.files should have size 2
    response.nextPageToken should be(Some("testTokenToAccessTheNextPage"))
  }

  it should "handle a folder response without a next token" in {
    val response = parseFolderResponse("/folderResponse.json")

    response.nextPageToken should be(None)
  }

  it should "serialize a WritableFile with all properties" in {
    val file = WritableFile(name = Some("test.json"), mimeType = Some("application/json"),
      parents = Some(List("parent_ynvAECVBSOx0123456789")), description = Some("This is a test file."),
      createdTime = Some(Instant.parse("2021-08-14T19:23:05Z")),
      modifiedTime = Some(Instant.parse("2021-08-14T19:23:22Z")),
      properties = Some(Map("test" -> "yes", "foo" -> "baz")),
      appProperties = Some(Map("appTest" -> "yeah", "appFoo" -> "appBaz")),
      trashed = Some(true), trashedTime = Some(Instant.parse("2021-09-10T18:41:57.365Z")))
    val expResult = readDataFile(resourceFile("/writableFile.json"))

    val json = file.toJson.compactPrint
    json should be(expResult)
  }

  it should "serialize a WritableFile with no properties" in {
    val file = WritableFile(name = None, mimeType = None, parents = None, description = None,
      createdTime = None, modifiedTime = None, properties = None, appProperties = None)

    val json = file.toJson.compactPrint
    json should be("{}")
  }
}
