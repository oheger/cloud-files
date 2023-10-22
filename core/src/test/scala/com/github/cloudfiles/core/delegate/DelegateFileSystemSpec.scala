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

package com.github.cloudfiles.core.delegate

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.DelegateFileSystemSpec.{FileType, FolderContentType, FolderType}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.{verify, when}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.reflect.ClassTag

object DelegateFileSystemSpec {
  /** The type for the files in the test file system. */
  type FileType = Model.File[String]

  /** The type for the folders in the test file system. */
  type FolderType = Model.Folder[String]

  /** The type for the folder content in the test file system. */
  type FolderContentType = Model.FolderContent[String, FileType, FolderType]
}

/**
 * Test class for ''DelegateFileSystem''.
 */
class DelegateFileSystemSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar {
  /**
   * Creates a test delegate file system that uses a mock delegate.
   *
   * @return the test delegate file system
   */
  private def createDelegateFileSystem(): DelegateFileSystem[String, FileType, FolderType] = {
    val delegateMock = mock[ExtensibleFileSystem[String, FileType, FolderType, FolderContentType]]
    new DelegateFileSystem[String, FileType, FolderType] {
      override val delegate: ExtensibleFileSystem[String, FileType, FolderType, FolderContentType] = delegateMock
    }
  }

  /**
   * Convenience function to create a mock for an ''Operation'' of the given
   * result type.
   *
   * @param ct the class tag for the operation's result type
   * @tparam A the result type of the operation
   * @return the mock for the operation
   */
  private def operationMock[A](implicit ct: ClassTag[A]): Operation[A] = mock[Operation[A]]

  "DelegateFileSystem" should "return the root ID" in {
    val fs = createDelegateFileSystem()
    val op = operationMock[String]
    when(fs.delegate.rootID).thenReturn(op)

    fs.rootID should be(op)
  }

  it should "resolve a path" in {
    val path = "/the/path/to/resolve"
    val fs = createDelegateFileSystem()
    val op = operationMock[String]
    when(fs.delegate.resolvePath(path)).thenReturn(op)

    fs.resolvePath(path) should be(op)
  }

  it should "resolve path components" in {
    val components = Seq("the", "single", "path", "components")
    val fs = createDelegateFileSystem()
    val op = operationMock[String]
    when(fs.delegate.resolvePathComponents(components)).thenReturn(op)

    fs.resolvePathComponents(components) should be(op)
  }

  it should "resolve a file" in {
    val FileID = "myFileID"
    val fs = createDelegateFileSystem()
    val op = operationMock[FileType]
    when(fs.delegate.resolveFile(FileID)).thenReturn(op)

    fs.resolveFile(FileID) should be(op)
  }

  it should "resolve a folder" in {
    val FolderID = "myFolderID"
    val fs = createDelegateFileSystem()
    val op = operationMock[FolderType]
    when(fs.delegate.resolveFolder(FolderID)).thenReturn(op)

    fs.resolveFolder(FolderID) should be(op)
  }

  it should "return the content of a folder" in {
    val FolderID = "myFolderWithContentID"
    val fs = createDelegateFileSystem()
    val op = operationMock[FolderContentType]
    when(fs.delegate.folderContent(FolderID)).thenReturn(op)

    fs.folderContent(FolderID) should be(op)
  }

  it should "create a folder" in {
    val ParentID = "newFolderParent"
    val folder = mock[Model.Folder[String]]
    val op = operationMock[String]
    val fs = createDelegateFileSystem()
    when(fs.delegate.createFolder(ParentID, folder)).thenReturn(op)

    fs.createFolder(ParentID, folder) should be(op)
  }

  it should "update a folder" in {
    val folder = mock[Model.Folder[String]]
    val op = operationMock[Unit]
    val fs = createDelegateFileSystem()
    when(fs.delegate.updateFolder(folder)).thenReturn(op)

    fs.updateFolder(folder) should be(op)
  }

  it should "delete a folder" in {
    val FolderID = "folderToDelete"
    val op = operationMock[Unit]
    val fs = createDelegateFileSystem()
    when(fs.delegate.deleteFolder(FolderID)).thenReturn(op)

    fs.deleteFolder(FolderID) should be(op)
  }

  it should "create a file" in {
    val ParentID = "newFileParent"
    val file = mock[Model.File[String]]
    val source = Source.single(ByteString("some file data"))
    val op = operationMock[String]
    val fs = createDelegateFileSystem()
    when(fs.delegate.createFile(ParentID, file, source)).thenReturn(op)

    fs.createFile(ParentID, file, source) should be(op)
  }

  it should "update a file" in {
    val file = mock[Model.File[String]]
    val op = operationMock[Unit]
    val fs = createDelegateFileSystem()
    when(fs.delegate.updateFile(file)).thenReturn(op)

    fs.updateFile(file) should be(op)
  }

  it should "update the content of a file" in {
    val FileID = "fileToUpdate"
    val FileSize = 20210212215927L
    val source = Source.single(ByteString("some updated data"))
    val op = operationMock[Unit]
    val fs = createDelegateFileSystem()
    when(fs.delegate.updateFileContent(FileID, FileSize, source)).thenReturn(op)

    fs.updateFileContent(FileID, FileSize, source) should be(op)
  }

  it should "download a file" in {
    val FileID = "fileToDownload"
    val op = operationMock[HttpEntity]
    val fs = createDelegateFileSystem()
    when(fs.delegate.downloadFile(FileID)).thenReturn(op)

    fs.downloadFile(FileID) should be(op)
  }

  it should "delete a file" in {
    val FileID = "fileToDelete"
    val op = operationMock[Unit]
    val fs = createDelegateFileSystem()
    when(fs.delegate.deleteFile(FileID)).thenReturn(op)

    fs.deleteFile(FileID) should be(op)
  }

  it should "patch a folder" in {
    val srcFolder = mock[Model.Folder[String]]
    val patchedFolder = mock[FolderType]
    val spec = ElementPatchSpec(patchName = Some("aPatchedName"))
    val fs = createDelegateFileSystem()
    when(fs.delegate.patchFolder(srcFolder, spec)).thenReturn(patchedFolder)

    fs.patchFolder(srcFolder, spec) should be(patchedFolder)
  }

  it should "patch a file" in {
    val srcFile = mock[Model.File[String]]
    val patchedFile = mock[FileType]
    val spec = ElementPatchSpec(patchSize = Some(42))
    val fs = createDelegateFileSystem()
    when(fs.delegate.patchFile(srcFile, spec)).thenReturn(patchedFile)

    fs.patchFile(srcFile, spec) should be(patchedFile)
  }

  it should "close the underlying FileSystem" in {
    val fs = createDelegateFileSystem()

    fs.close()
    verify(fs.delegate).close()
  }
}
