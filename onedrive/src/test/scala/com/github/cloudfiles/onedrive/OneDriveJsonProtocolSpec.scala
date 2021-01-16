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

/**
 * Test class for ''OneDriveJsonProtocol''.
 */
class OneDriveJsonProtocolSpec extends AnyFlatSpec with Matchers with FileTestHelper {
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
}