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
    googleFolder.description should be(file.description.get)
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
    googleFile.description should be(null)
    googleFile.size should be(1234)
  }
}
