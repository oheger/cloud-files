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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * Test class for ''GoogleDriveModel''.
 */
class GoogleDriveModelSpec extends AnyFlatSpec with Matchers {
  "GoogleDriveModel" should "provide a Folder implementation" in {
    val file = GoogleDriveJsonProtocol.File(id = "folderID", name = "testFolder", description = Some("testDesc"),
      mimeType = "application/vnd.google-apps.folder", parents = List("testParent"),
      createdTime = Instant.parse("2021-08-14T20:08:57.00Z"),
      modifiedTime = Instant.parse("2021-08-14T20:09:24.05Z"), size = None,
      properties = Some(Map("folder" -> "extra")), appProperties = None)

    val googleFolder = GoogleDriveModel.GoogleDriveFolder(file)
    googleFolder.id should be(file.id)
    googleFolder.name should be(file.name)
    googleFolder.createdAt should be(file.createdTime)
    googleFolder.lastModifiedAt should be(file.modifiedTime)
    googleFolder.description should be(file.description)
  }

  it should "provide a File implementation" in {
    val file = GoogleDriveJsonProtocol.File(id = "fileID", name = "testFile", description = None,
      mimeType = "application/test", parents = List("testParent"),
      createdTime = Instant.parse("2021-08-14T20:16:02.77Z"),
      modifiedTime = Instant.parse("2021-08-14T20:16:15.55Z"), size = Some("1234"),
      properties = None, appProperties = Some(Map("file" -> "appProperty")))

    val googleFile = GoogleDriveModel.GoogleDriveFile(file)
    googleFile.id should be(file.id)
    googleFile.name should be(file.name)
    googleFile.createdAt should be(file.createdTime)
    googleFile.lastModifiedAt should be(file.modifiedTime)
    googleFile.description should be(None)
    googleFile.size should be(1234)
  }

  it should "create a new GoogleDriveFolder with the properties provided" in {
    val FolderID = "theFolderID"
    val FolderName = "MyTestFolder"
    val FolderDescription = "The description of my folder"
    val FolderCreationTime = Instant.parse("2021-08-15T16:36:57.11Z")
    val FolderModifiedTime = Instant.parse("2021-08-15T16:37:15.22Z")
    val FolderProperties = Map("prop1" -> "value1", "prop2" -> "value2")
    val FolderAppProperties = Map("someAppProperty" -> "someValue")
    val expFile = GoogleDriveJsonProtocol.File(id = FolderID, name = FolderName,
      mimeType = "application/vnd.google-apps.folder", parents = List.empty, createdTime = FolderCreationTime,
      modifiedTime = FolderModifiedTime, description = Some(FolderDescription), size = None,
      properties = Some(FolderProperties), appProperties = Some(FolderAppProperties))

    val folder = GoogleDriveModel.newFolder(id = FolderID, name = FolderName, description = FolderDescription,
      createdAt = FolderCreationTime, lastModifiedAt = FolderModifiedTime, properties = FolderProperties,
      appProperties = FolderAppProperties)
    folder.googleFile should be(expFile)
  }

  it should "create a new GoogleDriveFolder with default values" in {
    val expFile = GoogleDriveJsonProtocol.File(id = null, name = null, mimeType = "application/vnd.google-apps.folder",
      parents = List.empty, createdTime = null, modifiedTime = null, description = None, size = None,
      properties = None, appProperties = None)

    val folder = GoogleDriveModel.newFolder()
    folder.googleFile should be(expFile)
  }

  it should "deal with null values for maps when creating a new GoogleDriveFolder" in {
    val folder = GoogleDriveModel.newFolder(properties = null, appProperties = null)

    folder.googleFile.properties should be(empty)
    folder.googleFile.appProperties should be(empty)
  }

  it should "create a new GoogleDriveFile with the properties provided" in {
    val FileID = "theFileID"
    val FileName = "MyTestFile.json"
    val FileMimeType = "application/json"
    val FileSize = 65537
    val FileDescription = "The description of my file"
    val FileCreationTime = Instant.parse("2021-08-15T16:59:49.33Z")
    val FileModifiedTime = Instant.parse("2021-08-15T17:02:02.44Z")
    val FileProperties = Map("prop1" -> "value1", "prop2" -> "value2")
    val FileAppProperties = Map("someAppProperty" -> "someValue")
    val expFile = GoogleDriveJsonProtocol.File(id = FileID, name = FileName,
      mimeType = FileMimeType, parents = List.empty, createdTime = FileCreationTime,
      modifiedTime = FileModifiedTime, description = Some(FileDescription), size = Some(FileSize.toString),
      properties = Some(FileProperties), appProperties = Some(FileAppProperties))

    val file = GoogleDriveModel.newFile(id = FileID, name = FileName, createdAt = FileCreationTime,
      lastModifiedAt = FileModifiedTime, description = FileDescription, properties = FileProperties,
      appProperties = FileAppProperties, mimeType = FileMimeType, size = FileSize)
    file.googleFile should be(expFile)
  }

  it should "create a new GoogleDriveFile with default values" in {
    val expFile = GoogleDriveJsonProtocol.File(id = null, name = null, mimeType = null,
      parents = List.empty, createdTime = null, modifiedTime = null, description = None, size = None,
      properties = None, appProperties = None)

    val file = GoogleDriveModel.newFile()
    file.googleFile should be(expFile)
  }
}
