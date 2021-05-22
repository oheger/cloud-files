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

package com.github.cloudfiles.localfs

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import java.time.Instant

class LocalFsModelSpec extends AnyFlatSpec with Matchers {
  "LocalFsModel" should "create a folder object for a create operation" in {
    val Name = "newFolder"
    val LastModified = Instant.parse("2021-05-22T09:38:42.000Z")

    val folder = LocalFsModel.newFolder(Name, lastModifiedAt = Some(LastModified))
    folder.id should be(null)
    folder.name should be(Name)
    folder.description should be(null)
    folder.createdAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedUpdate should be(Some(LastModified))
  }

  it should "create a folder for an update operation" in {
    val TestPath = Paths.get("the", "test", "path")
    val LastModified = Instant.parse("2021-05-22T09:45:28.000Z")

    val folder = LocalFsModel.updateFolder(TestPath, Some(LastModified))
    folder.id should be(TestPath)
    folder.name should be("path")
    folder.description should be(null)
    folder.createdAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedUpdate should be(Some(LastModified))
  }

  it should "handle an empty path when constructing an update folder" in {
    val folder = LocalFsModel.updateFolder(Paths.get("/"))

    folder.name should be(null)
  }

  it should "create a file object for a create operation" in {
    val Name = "newFile"
    val LastModified = Instant.parse("2021-05-22T14:39:38.000Z")

    val file = LocalFsModel.newFile(Name, Some(LastModified))
    file.id should be(null)
    file.name should be(Name)
    file.description should be(null)
    file.createdAt should be(LocalFsModel.TimeUndefined)
    file.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    file.size should be(0)
    file.lastModifiedUpdate should be(Some(LastModified))
  }

  it should "create a file object for an update operation" in {
    val Name = "newFile.txt"
    val TestPath = Paths.get("path", "to", "file", Name)
    val LastModified = Instant.parse("2021-05-22T14:47:39.000Z")

    val file = LocalFsModel.updateFile(TestPath, Some(LastModified))
    file.id should be(TestPath)
    file.name should be(Name)
    file.description should be(null)
    file.createdAt should be(LocalFsModel.TimeUndefined)
    file.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    file.size should be(0)
    file.lastModifiedUpdate should be(Some(LastModified))
  }
}
