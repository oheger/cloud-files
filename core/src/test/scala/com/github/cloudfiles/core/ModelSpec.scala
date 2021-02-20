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

package com.github.cloudfiles.core

import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
 * Test class for ''Model'' and the classes it defines.
 */
class ModelSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /**
   * Prepares a mock for an element to return the properties provided.
   *
   * @param elem the element mock
   * @param id   the ID
   * @param name the name
   */
  private def initElementMock(elem: Model.Element[String], id: String, name: String): Unit = {
    when(elem.id).thenReturn(id)
    when(elem.name).thenReturn(name)
  }

  /**
   * Creates a mock for a file that returns the properties provided.
   *
   * @param id   the ID
   * @param name the name
   * @return the mock file
   */
  private def fileMock(id: String, name: String): Model.File[String] = {
    val file = mock[Model.File[String]]
    initElementMock(file, id, name)
    file
  }

  /**
   * Creates a mock for a folder that returns the properties provided.
   *
   * @param id   the ID
   * @param name the name
   * @return the mock folder
   */
  private def folderMock(id: String, name: String): Model.Folder[String] = {
    val folder = mock[Model.Folder[String]]
    initElementMock(folder, id, name)
    folder
  }

  "FolderContent" should "map files and folders" in {
    val mapFile: Model.File[String] => Model.File[String] =
      file => fileMock(file.id, file.name + "_mapped")
    val mapFolder: Model.Folder[String] => Model.Folder[String] =
      folder => folderMock(folder.id, folder.name + "_mapped")
    val file1 = fileMock("f1", "file1")
    val file2 = fileMock("f2", "file2")
    val folder = folderMock("fo1", "folder1")
    val content = Model.FolderContent("someFolderID",
      Map("f1" -> file1, "f2" -> file2),
      Map("fo1" -> folder))

    val mappedContent = content.mapContent(mapFiles = Some(mapFile), mapFolders = Some(mapFolder))
    mappedContent.folderID should be(content.folderID)
    mappedContent.files should have size 2
    mappedContent.files("f1").name should be("file1_mapped")
    mappedContent.files("f2").name should be("file2_mapped")
    mappedContent.folders should have size 1
    mappedContent.folders("fo1").name should be("folder1_mapped")
  }

  it should "deal with undefined mapping functions" in {
    val content = Model.FolderContent("someFolderID",
      Map("fi1" -> fileMock("fi1", "oneFile.txt"),
        "fi2" -> fileMock("fi2", "anotherFile.doc")),
      Map("fo1" -> folderMock("fo1", "someFolder"),
        "fo2" -> folderMock("fo2", "oneMoreFolder")))

    val mappedContent = content.mapContent()
    mappedContent should be(content)
    mappedContent.files shouldBe theSameInstanceAs(content.files)
    mappedContent.folders shouldBe theSameInstanceAs(content.folders)
  }
}
