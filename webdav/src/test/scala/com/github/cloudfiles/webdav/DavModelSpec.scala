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

package com.github.cloudfiles.webdav

import akka.http.scaladsl.model.Uri
import com.github.cloudfiles.webdav.DavModel.{AttributeKey, Attributes}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Test class for functionality of the model classes of the DAV module. Basic
 * functionality is already tested when using the model objects in other tests.
 * So this class focuses on some specialities.
 */
class DavModelSpec extends AnyFlatSpec with Matchers {
  "Attributes" should "support adding an attribute by its key" in {
    val key = AttributeKey("urn:foo", "myKey")
    val attributesOrg = Attributes(Map.empty)

    val attributes = attributesOrg.withAttribute(key, "bar")
    attributes.keysToDelete should be(attributesOrg.keysToDelete)
    attributes.values should have size 1
    attributes.values(key) should be("bar")
  }

  it should "support adding an attribute using a namespace key pair" in {
    val key = AttributeKey("urn:foo", "myKey")
    val attributesOrg = Attributes(Map.empty)

    val attributes = attributesOrg.withAttribute(key.namespace, key.key, "bar")
    attributes.keysToDelete should be(attributesOrg.keysToDelete)
    attributes.values should have size 1
    attributes.values(key) should be("bar")
  }

  "DavModel" should "provide an empty Attributes instance" in {
    DavModel.EmptyAttributes.values should have size 0
    DavModel.EmptyAttributes.keysToDelete should have size 0
  }

  it should "create a new folder with all properties" in {
    val FolderName = "MyNewFolder"
    val FolderUri = Uri("https://test.example.org/test/folder")
    val FolderDesc = "This is my new folder. Indeed."
    val FolderAttrs = DavModel.Attributes(Map(DavModel.AttributeKey("ns", "attr") -> "someValue"))
    val ExpFolder = DavModel.DavFolder(id = FolderUri, createdAt = null, lastModifiedAt = null, name = FolderName,
      description = FolderDesc, attributes = FolderAttrs)

    val folder = DavModel.newFolder(name = FolderName, description = FolderDesc, id = FolderUri,
      attributes = FolderAttrs)
    folder should be(ExpFolder)
  }

  it should "create a new folder with default properties" in {
    val FolderName = "NewFolderWithDefaults"
    val ExpFolder = DavModel.DavFolder(id = null, createdAt = null, lastModifiedAt = null, name = FolderName,
      description = null, attributes = DavModel.EmptyAttributes)

    val folder = DavModel.newFolder(FolderName)
    folder should be(ExpFolder)
  }

  it should "create a new file with all properties" in {
    val FileName = "MyNewFile"
    val FileUri = Uri("https://test.example.org/test/data/newFile.txt")
    val FileSize = 20210110
    val FileDesc = "A new test file"
    val FileAttrs = DavModel.Attributes(Map(DavModel.AttributeKey("ns", "attr") -> "someFileValue"))
    val ExpFile = DavModel.DavFile(id = FileUri, createdAt = null, lastModifiedAt = null, name = FileName,
      description = FileDesc, size = FileSize, attributes = FileAttrs)

    val file = DavModel.newFile(FileName, FileSize, description = FileDesc, attributes = FileAttrs, id = FileUri)
    file should be(ExpFile)
  }

  it should "create a new file with default properties" in {
    val FileName = "MyNewFile"
    val FileSize = 20210110
    val ExpFile = DavModel.DavFile(id = null, createdAt = null, lastModifiedAt = null, name = FileName,
      description = null, size = FileSize, attributes = DavModel.EmptyAttributes)

    val file = DavModel.newFile(FileName, FileSize)
    file should be(ExpFile)
  }
}
