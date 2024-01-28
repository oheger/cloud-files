/*
 * Copyright 2020-2024 The Developers Team.
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
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.util.{Failure, Success, Try}

object ModelSpec {
  /**
   * Generates the name of a file that has been mapped by a mapping function.
   *
   * @param file the original file
   * @return the mapped name of this file
   */
  private def mappedFileName(file: Model.File[String]): String =
    file.name + "_mappedFile"

  /**
   * Generates the name of a folder that has been mapped by a mapping function.
   *
   * @param folder the original folder
   * @return the mapped name of this folder
   */
  private def mappedFolderName(folder: Model.Folder[String]): String =
    folder.name + "_mappedFolder"
}

/**
 * Test class for ''Model'' and the classes it defines.
 */
class ModelSpec extends AsyncFlatSpec with Matchers with MockitoSugar {

  import ModelSpec._

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

  /**
   * A test mapping function for files that updates the file name.
   *
   * @param file the file to map
   * @return the mapped file
   */
  private def mapFile(file: Model.File[String]): Model.File[String] =
    fileMock(file.id, mappedFileName(file))

  /**
   * A test mapping function for folders that updates the folder name.
   *
   * @param folder the folder to map
   * @return the mapped folder
   */
  private def mapFolder(folder: Model.Folder[String]): Model.Folder[String] =
    folderMock(folder.id, mappedFolderName(folder))

  "FolderContent" should "map files and folders" in {
    val file1 = fileMock("f1", "file1")
    val file2 = fileMock("f2", "file2")
    val folder = folderMock("fo1", "folder1")
    val content = Model.FolderContent("someFolderID",
      Map(file1.id -> file1, file2.id -> file2),
      Map(folder.id -> folder))

    val mappedContent = content.mapContent(mapFiles = Some(mapFile), mapFolders = Some(mapFolder))
    mappedContent.folderID should be(content.folderID)
    mappedContent.files should have size 2
    mappedContent.files(file1.id).name should be(mappedFileName(file1))
    mappedContent.files(file2.id).name should be(mappedFileName(file2))
    mappedContent.folders should have size 1
    mappedContent.folders(folder.id).name should be(mappedFolderName(folder))
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

  it should "map files and folders in parallel" in {
    val file1 = fileMock("f1", "file1")
    val file2 = fileMock("f2", "file2")
    val folder1 = folderMock("fo1", "folder1")
    val folder2 = folderMock("fo2", "folder2")
    val folder3 = folderMock("fo3", "folder3")
    val content = Model.FolderContent("someFolderID",
      Map(file1.id -> file1, file2.id -> file2),
      Map(folder1.id -> folder1, folder2.id -> folder2, folder3.id -> folder3))

    content.mapContentParallel(mapFiles = Some(mapFile), mapFolders = Some(mapFolder)) map { mappedContent =>
      mappedContent.folderID should be(content.folderID)
      mappedContent.files should have size 2
      mappedContent.files(file1.id).name should be(mappedFileName(file1))
      mappedContent.files(file2.id).name should be(mappedFileName(file2))
      mappedContent.folders should have size 3
      mappedContent.folders(folder1.id).name should be(mappedFolderName(folder1))
      mappedContent.folders(folder2.id).name should be(mappedFolderName(folder2))
      mappedContent.folders(folder3.id).name should be(mappedFolderName(folder3))
    }
  }

  it should "deal with undefined mapping functions when mapping in parallel" in {
    val content = Model.FolderContent("someFolderID",
      Map("fi1" -> fileMock("fi1", "oneFile.txt"),
        "fi2" -> fileMock("fi2", "anotherFile.doc")),
      Map("fo1" -> folderMock("fo1", "someFolder"),
        "fo2" -> folderMock("fo2", "oneMoreFolder")))

    content.mapContentParallel() map { mappedContent =>
      mappedContent should be(content)
      mappedContent.files shouldBe theSameInstanceAs(content.files)
      mappedContent.folders shouldBe theSameInstanceAs(content.folders)
    }
  }

  it should "return a failed future if a parallel mapping fails" in {
    val file = fileMock("f1", "file1")
    val folder = folderMock("fo1", "folder1")
    val content = Model.FolderContent("someFolderID",
      Map(file.id -> file),
      Map(folder.id -> folder))
    val expectedException = new IllegalArgumentException("Unsupported mapping")

    def mapFileFailure(file: Model.File[String]): Model.File[String] = throw expectedException

    recoverToExceptionIf[IllegalArgumentException] {
      content.mapContentParallel(Some(mapFileFailure))
    } map { exception =>
      exception should be(expectedException)
    }
  }

  it should "map files and folders with Try in parallel if mapping is successful" in {
    val file1 = fileMock("f1", "file1")
    val file2 = fileMock("f2", "file2")
    val folder1 = folderMock("fo1", "folder1")
    val folder2 = folderMock("fo2", "folder2")
    val folder3 = folderMock("fo3", "folder3")
    val content = Model.FolderContent("someFolderID",
      Map(file1.id -> file1, file2.id -> file2),
      Map(folder1.id -> folder1, folder2.id -> folder2, folder3.id -> folder3))

    def mapFileTried(file: Model.File[String]): Try[Model.File[String]] = Try {
      mapFile(file)
    }

    def mapFolderTried(folder: Model.Folder[String]): Try[Model.Folder[String]] = Try {
      mapFolder(folder)
    }

    content.mapContentParallelTried(mapFiles = Some(mapFileTried), mapFolders = Some(mapFolderTried)) map { t =>
      val (mappedContent, failures) = t
      mappedContent.folderID should be(content.folderID)
      mappedContent.files should have size 2
      mappedContent.files(file1.id).name should be(mappedFileName(file1))
      mappedContent.files(file2.id).name should be(mappedFileName(file2))
      mappedContent.folders should have size 3
      mappedContent.folders(folder1.id).name should be(mappedFolderName(folder1))
      mappedContent.folders(folder2.id).name should be(mappedFolderName(folder2))
      mappedContent.folders(folder3.id).name should be(mappedFolderName(folder3))
      failures shouldBe empty
    }
  }

  it should "map files and folders with Try in parallel if mappings fail" in {
    val ErrorName = "Error"

    def mapFileTried(file: Model.File[String]): Try[Model.File[String]] =
      if (file.name.contains(ErrorName)) Failure(new IllegalArgumentException(file.name))
      else Success(mapFile(file))

    def mapFolderTried(folder: Model.Folder[String]): Try[Model.Folder[String]] =
      if (folder.name.contains(ErrorName)) Failure(new IllegalArgumentException(folder.name))
      else Success(mapFolder(folder))

    val file1 = fileMock("f1", "file1")
    val file2 = fileMock("f2", ErrorName + ".txt")
    val folder1 = folderMock("fo1", ErrorName)
    val folder2 = folderMock("fo2", "folder2")
    val content = Model.FolderContent("someFolderID",
      Map(file1.id -> file1, file2.id -> file2),
      Map(folder1.id -> folder1, folder2.id -> folder2))

    content.mapContentParallelTried(mapFiles = Some(mapFileTried), mapFolders = Some(mapFolderTried)) map { t =>
      val (mappedContent, failures) = t
      mappedContent.folderID should be(content.folderID)
      mappedContent.files should have size 1
      mappedContent.files(file1.id).name should be(mappedFileName(file1))
      mappedContent.folders should have size 1
      mappedContent.folders(folder2.id).name should be(mappedFolderName(folder2))
      failures should have size 2
      failures.forall(_.exception.isInstanceOf[IllegalArgumentException])
      failures.map(_.exception.getMessage) should contain only(ErrorName, ErrorName + ".txt")
    }
  }

  it should "deal with undefined mapping functions when mapping with Try in parallel" in {
    val content = Model.FolderContent("someFolderID",
      Map("fi1" -> fileMock("fi1", "oneFile.txt"),
        "fi2" -> fileMock("fi2", "anotherFile.doc")),
      Map("fo1" -> folderMock("fo1", "someFolder"),
        "fo2" -> folderMock("fo2", "oneMoreFolder")))

    content.mapContentParallelTried() map { t =>
      val (mappedContent, failures) = t
      mappedContent should be(content)
      mappedContent.files shouldBe theSameInstanceAs(content.files)
      mappedContent.folders shouldBe theSameInstanceAs(content.folders)
      failures shouldBe empty
    }
  }
}
