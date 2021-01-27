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

import com.github.cloudfiles.core.FileTestHelper
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant

/**
 * Test class for ''OneDriveJsonProtocol''.
 */
class OneDriveJsonProtocolSpec extends AnyFlatSpec with Matchers with FileTestHelper {
  /**
   * Checks whether a timestamp has the expected value.
   *
   * @param actual   the actual value
   * @param expected the string representation of the expected value
   */
  private def checkInstant(actual: Instant, expected: String): Unit = {
    actual should be(Instant.parse(expected))
  }

  /**
   * Parses the JSON file with the response of a folder content request.
   *
   * @return the parsed ''FolderResponse''
   */
  private def parseFolderResponse(): FolderResponse = {
    val responseJson = readDataFile(resourceFile("/folderResponse.json"))
    val jsonAst = responseJson.parseJson
    val folderResponse = jsonAst.convertTo[FolderResponse]
    folderResponse.value should have size 3
    folderResponse
  }

  /**
   * Serializes the given item to its JSON representation.
   *
   * @param item the item
   * @return the JSON string for this item
   */
  private def toJson(item: WritableDriveItem): String = {
    val ast = item.toJson
    ast.prettyPrint
  }

  "OneDriveJsonProtocol" should "de-serialize a folder from a folder content response" in {
    val folderResponse = parseFolderResponse()

    val folder = folderResponse.value.head
    folder.id should not be null
    folder.name should be("folder (1)")
    folder.file should be(None)
    val folderData = folder.folder.get
    folderData.childCount should be(1)
    val folderCreated = folder.createdBy
    folderCreated.device.map(_.id) should be(Some("18400086c36a98"))
    folderCreated.application.flatMap(_.displayName) should be(Some("MSOffice15"))
    folderCreated.user.flatMap(_.displayName) should be(Some("Test User"))
    folderCreated.group should be(None)
    val folderParent = folder.parentReference.get
    folderParent.driveId should be("xxxyyyzzz1234567")
    folderParent.driveType should be("personal")
    folderParent.id should be("xxxyyyzzz1234567!103")
    folderParent.path should be(Some("/drive/root:"))
    folderParent.listId should be(None)
    folderParent.shareId should be(None)
    folderParent.siteId should be(None)
  }

  it should "de-serialize a file from a folder content response" in {
    val folderResponse = parseFolderResponse()

    val file = folderResponse.value(2)
    file.name should be("file (1).mp3")
    file.id should be("xxxyyyzzz1234567!26990")
    file.size should be(100)
    file.description should be(Some("A test file"))
    val fileModified = file.lastModifiedBy
    fileModified.application.flatMap(_.displayName) should be(Some("StreamSync"))
    fileModified.user.flatMap(_.displayName) should be(Some("Test User"))
    file.parentReference.isDefined shouldBe true
    file.folder should be(None)
    val fileData = file.file.get
    fileData.mimeType should be("application/json")
    fileData.hashes.sha1Hash should be(Some("579087E1CDCBEC248B159C33355E7599538A533A"))
    fileData.hashes.quickXorHash should be(Some("IGwtls2AaRDoB2jk3pfomXh6FeQ="))
    file.specialFolder should be(None)
  }

  it should "de-serialize a specialized folder" in {
    val folderResponse = parseFolderResponse()

    folderResponse.value.head.specialFolder.map(_.name) should be(Some("photos"))
    folderResponse.value(1).specialFolder should be(None)
  }

  it should "de-serialize timestamps" in {
    val folderResponse = parseFolderResponse()

    checkInstant(folderResponse.value.head.createdDateTime, "2017-10-29T10:07:55.113Z")
    val file = folderResponse.value(2)
    checkInstant(file.lastModifiedDateTime, "2019-10-27T16:45:12.627Z")
    val fileSystemInfo = file.fileSystemInfo
    checkInstant(fileSystemInfo.createdDateTime, "2019-10-27T16:45:12.443Z")
    checkInstant(fileSystemInfo.lastModifiedDateTime, "2018-09-19T20:10:00Z")
    fileSystemInfo.lastAccessedDateTime should be(None)
  }

  it should "handle an unexpected timestamp data type during de-serialization" in {
    val responseJson = readDataFile(resourceFile("/folderResponse.json"))
    val modifiedJson = responseJson.replace("\"2019-10-27T16:45:12.443Z\"", "42")
    val jsonAst = modifiedJson.parseJson

    intercept[DeserializationException] {
      jsonAst.convertTo[FolderResponse]
    }
  }

  it should "serialize a writable drive item" in {
    val createdTimeStr = "2021-01-27T20:46:43.147Z"
    val createdTime = Instant.parse(createdTimeStr)
    val fsi = WritableFileSystemInfo(createdDateTime = Some(createdTime))
    val item = WritableDriveItem(name = Some("myItem"), fileSystemInfo = Some(fsi), file = Some(MarkerProperty()))

    val json = toJson(item)
    json should include regex """"name"\s*:\s*"myItem""""
    json should include regex (""""createdDateTime"\s*:\s*"""" + createdTimeStr)
    json should include regex """"file"\s*:\s*\{\W*\}"""
    json should not include "\"folder\""
  }

  it should "serialize a writable drive item without a fileSystemInfo" in {
    val item = WritableDriveItem(description = Some("desc"))

    val json = toJson(item)
    json should not include "\"fileSystemInfo\""
    json should include regex """"description"\s*:\s*"desc""""
  }
}
