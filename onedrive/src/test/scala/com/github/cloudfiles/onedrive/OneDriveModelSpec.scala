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

import com.github.cloudfiles.onedrive.OneDriveJsonProtocol.{DriveItem, FileSystemInfo, Hashes, IdentitySet}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

object OneDriveModelSpec {
  /** Constant for an identity set that is not relevant. */
  private val EmptyIdentities = IdentitySet(None, None, None, None)

  /** A test drive item used as data for OneDrive elements. */
  private val TestDriveItem = DriveItem(id = "testDriveItemID",
    createdDateTime = Instant.parse("2021-01-15T12:11:42.123Z"),
    lastModifiedDateTime = Instant.parse("2021-01-16T20:56:43.443Z"),
    name = "A test item", size = 16384, webUrl = "https://someUri.com/testItem",
    description = Some("Description of the test item"), shared = None, parentReference = None,
    fileSystemInfo = FileSystemInfo(Instant.parse("2021-01-15T12:00:10.129Z"),
      Instant.parse("2021-01-15T12:06:49.123Z"), None),
    createdBy = EmptyIdentities, lastModifiedBy = EmptyIdentities, specialFolder = None,
    file = Some(OneDriveJsonProtocol.File("some/mime-type", Hashes(None, None, Some("xorHash")))),
    folder = Some(OneDriveJsonProtocol.Folder(128)))
}

/**
 * Test class for ''OneDriveModel''.
 */
class OneDriveModelSpec extends AnyFlatSpec with Matchers {

  import OneDriveModelSpec._

  "OneDriveElement" should "provide access to the ID property" in {
    val folder = OneDriveModel.OneDriveFolder(TestDriveItem)

    folder.id should be(TestDriveItem.id)
  }

  it should "provide access to the name property" in {
    val folder = OneDriveModel.OneDriveFolder(TestDriveItem)

    folder.name should be(TestDriveItem.name)
  }

  it should "provide access to the description property if it is defined" in {
    val folder = OneDriveModel.OneDriveFolder(TestDriveItem)

    folder.description should be(TestDriveItem.description.get)
  }

  it should "provide access to the description property if it is not defined" in {
    val item = TestDriveItem.copy(description = None)
    val folder = OneDriveModel.OneDriveFolder(item)

    folder.description should be(null)
  }

  it should "provide access to the createdAt property" in {
    val folder = OneDriveModel.OneDriveFolder(TestDriveItem)

    folder.createdAt should be(TestDriveItem.createdDateTime)
  }

  it should "provide access to the lastModifiedAt property" in {
    val folder = OneDriveModel.OneDriveFolder(TestDriveItem)

    folder.lastModifiedAt should be(TestDriveItem.lastModifiedDateTime)
  }

  "OneDriveFolder" should "return the folder data object" in {
    val folder = OneDriveModel.OneDriveFolder(TestDriveItem)

    folder.folderData should be(TestDriveItem.folder.get)
  }

  "OneDriveFile" should "provide access to the size property" in {
    val file = OneDriveModel.OneDriveFile(TestDriveItem)

    file.size should be(TestDriveItem.size)
  }

  it should "return the file data object" in {
    val file = OneDriveModel.OneDriveFile(TestDriveItem)

    file.fileData should be(TestDriveItem.file.get)
  }
}
