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

package com.github.cloudfiles.localfs

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import java.time.Instant

class LocalFsModelSpec extends AnyFlatSpec with Matchers {
  "LocalFsModel" should "create a folder object for a create operation" in {
    val path = Paths.get("test/folder/path")
    val Name = "newFolder"
    val LastModified = Instant.parse("2021-05-22T09:38:42.000Z")

    val folder = LocalFsModel.newFolder(name = Name, path = path, lastModifiedAt = Some(LastModified))
    folder.id should be(path)
    folder.name should be(Name)
    folder.description should be(None)
    folder.createdAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedUpdate should be(Some(LastModified))
  }

  it should "create a folder with default properties" in {
    val folder = LocalFsModel.newFolder()

    folder.id should be(null)
    folder.name should be(null)
    folder.description should be(None)
    folder.createdAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    folder.lastModifiedUpdate should be(None)
  }

  it should "derive the name from the path when creating a folder" in {
    val path = Paths.get("test/folder/path")

    val folder = LocalFsModel.newFolder(path)
    folder.id should be(path)
    folder.name should be("path")
  }

  it should "handle an empty path when constructing a folder" in {
    val folder = LocalFsModel.newFolder(Paths.get("/"))

    folder.name should be(null)
  }

  it should "create a file object for a create operation" in {
    val path = Paths.get("/some/test/file.txt")
    val Name = "newFile"
    val LastModified = Instant.parse("2021-05-22T14:39:38.000Z")

    val file = LocalFsModel.newFile(path, Name, Some(LastModified))
    file.id should be(path)
    file.name should be(Name)
    file.description should be(None)
    file.createdAt should be(LocalFsModel.TimeUndefined)
    file.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    file.size should be(0)
    file.lastModifiedUpdate should be(Some(LastModified))
  }

  it should "create a file object with default properties" in {
    val file = LocalFsModel.newFile()

    file.id should be(null)
    file.name should be(null)
    file.description should be(None)
    file.createdAt should be(LocalFsModel.TimeUndefined)
    file.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    file.size should be(0)
    file.lastModifiedUpdate should be(None)
  }

  it should "derive the file name from the path" in {
    val Name = "newFile.txt"
    val TestPath = Paths.get("path", "to", "file", Name)
    val LastModified = Instant.parse("2021-05-22T14:47:39.000Z")

    val file = LocalFsModel.newFile(TestPath, lastModifiedAt = Some(LastModified))
    file.id should be(TestPath)
    file.name should be(Name)
    file.description should be(None)
    file.createdAt should be(LocalFsModel.TimeUndefined)
    file.lastModifiedAt should be(LocalFsModel.TimeUndefined)
    file.size should be(0)
    file.lastModifiedUpdate should be(Some(LastModified))
  }
}
