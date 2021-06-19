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

package com.github.cloudfiles.crypt.fs

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper, Model}
import com.github.cloudfiles.crypt.fs.resolver.PathResolver
import org.mockito.Mockito.{verify, verifyZeroInteractions, when}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
 * Test class for ''CryptNamesFileSystem''.
 */
class CryptNamesFileSystemSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar
  with AsyncTestHelper {

  import CryptFileSystemTestHelper._

  /**
   * Creates a new crypt file system instance for a test case.
   *
   * @param optResolver an optional ''PathResolver'' to set
   * @return the new file system test instance
   */
  private def createCryptFileSystem(optResolver: Option[PathResolver[String, FileType, FolderType]] = None):
  CryptNamesFileSystem[String, FileType, FolderType] = {
    val delegate = mock[ExtensibleFileSystem[String, FileType, FolderType, ContentType]]
    optResolver.fold(new CryptNamesFileSystem[String, FileType, FolderType](delegate, DefaultCryptConfig)) { res =>
      new CryptNamesFileSystem[String, FileType, FolderType](delegate, DefaultCryptConfig, res)
    }
  }

  /**
   * Creates a mock for a file with the given properties.
   *
   * @param idx  the index of the test file
   * @param name the name of this file
   * @return the mock for this test file
   */
  private def createFileMock(idx: Int, name: String): FileType =
    initMock(mock[FileType], fileID(idx), name)

  /**
   * Creates a mock for a folder with the given properties.
   *
   * @param idx  the index of the test folder
   * @param name the name of this folder
   * @return the mock for this test folder
   */
  private def createFolderMock(idx: Int, name: String): FolderType =
    initMock(mock[FolderType], folderID(idx), name)

  "CryptNamesFileSystem" should "resolve a folder" in {
    val folder = createFolderMock(1, folderName(1))
    val cryptFolder = createFolderMock(1, encryptName(folder.name))
    val fs = createCryptFileSystem()
    when(fs.delegate.resolveFolder(FileID)).thenReturn(stubOperation(cryptFolder))
    when(fs.delegate.patchFolder(cryptFolder, ElementPatchSpec(patchName = Some(folder.name))))
      .thenReturn(folder)

    runOp(testKit, fs.resolveFolder(FileID)) should be(folder)
  }

  it should "resolve a file" in {
    val file = createFileMock(1, fileName(1))
    val cryptFile = createFileMock(1, encryptName(file.name))
    val fs = createCryptFileSystem()
    when(fs.delegate.resolveFile(FileID)).thenReturn(stubOperation(cryptFile))
    when(fs.delegate.patchFile(cryptFile, ElementPatchSpec(patchName = Some(file.name))))
      .thenReturn(file)

    runOp(testKit, fs.resolveFile(FileID)) should be(file)
  }

  it should "create a new folder" in {
    val ParentID = "theParentFolderID"
    val folder = createFolderMock(1, folderName(1))
    val cryptFolder = createFolderMock(1, encryptName(folder.name))
    val fs = createCryptFileSystem()
    when(fs.delegate.patchFolder(folder, ElementPatchSpec(patchName = Some(cryptFolder.name))))
      .thenReturn(cryptFolder)
    when(fs.delegate.createFolder(ParentID, cryptFolder)).thenReturn(stubOperation(FileID))

    runOp(testKit, fs.createFolder(ParentID, folder)) should be(FileID)
  }

  it should "create a new file" in {
    val ParentID = "someParentID"
    val file = createFileMock(1, fileName(1))
    val cryptFile = createFileMock(1, encryptName(file.name))
    val source = Source.single(ByteString(FileTestHelper.TestData))
    val fs = createCryptFileSystem()
    when(fs.delegate.patchFile(file, ElementPatchSpec(patchName = Some(cryptFile.name))))
      .thenReturn(cryptFile)
    when(fs.delegate.createFile(ParentID, cryptFile, source)).thenReturn(stubOperation(FileID))

    runOp(testKit, fs.createFile(ParentID, file, source)) should be(FileID)
  }

  it should "return the content of a folder" in {
    val files = Map(fileID(1) -> createFileMock(1, fileName(1)),
      fileID(2) -> createFileMock(2, fileName(2)),
      fileID(3) -> createFileMock(3, fileName(3)))
    val cryptFiles = Map(fileID(1) -> createFileMock(1, encryptName(fileName(1))),
      fileID(2) -> createFileMock(2, encryptName(fileName(2))),
      fileID(3) -> createFileMock(3, encryptName(fileName(3))))
    val folders = Map(folderID(1) -> createFolderMock(1, folderName(1)),
      folderID(2) -> createFolderMock(2, folderName(2)))
    val cryptFolders = Map(folderID(1) -> createFolderMock(1, encryptName(folderName(1))),
      folderID(2) -> createFolderMock(2, encryptName(folderName(2))))
    val cryptContent = Model.FolderContent(FileID, cryptFiles, cryptFolders)
    val content = Model.FolderContent(FileID, files, folders)
    val fs = createCryptFileSystem()
    cryptFiles foreach { e =>
      val plainFile = files(e._1)
      when(fs.delegate.patchFile(e._2, ElementPatchSpec(patchName = Some(plainFile.name))))
        .thenReturn(plainFile)
    }
    cryptFolders foreach { e =>
      val plainFolder = folders(e._1)
      when(fs.delegate.patchFolder(e._2, ElementPatchSpec(patchName = Some(plainFolder.name))))
        .thenReturn(plainFolder)
    }
    when(fs.delegate.folderContent(FileID)).thenReturn(stubOperation(cryptContent))

    runOp(testKit, fs.folderContent(FileID)) should be(content)
  }

  it should "resolve path components using the resolver" in {
    val components = Seq("the", "desired", "folder")
    val resolver = mock[PathResolver[String, FileType, FolderType]]
    val operation = mock[Operation[String]]
    val fs = createCryptFileSystem(optResolver = Some(resolver))
    when(resolver.resolve(components, fs.delegate, DefaultCryptConfig)).thenReturn(operation)

    fs.resolvePathComponents(components) should be(operation)
  }

  it should "resolve a path using the resolver" in {
    val Path = "/the/desired/test%20file.doc"
    val components = Seq("the", "desired", "test file.doc")
    val resolver = mock[PathResolver[String, FileType, FolderType]]
    val operation = mock[Operation[String]]
    val fs = createCryptFileSystem(optResolver = Some(resolver))
    when(resolver.resolve(components, fs.delegate, DefaultCryptConfig)).thenReturn(operation)

    fs.resolvePath(Path) should be(operation)
  }

  it should "resolve an empty path to the file system's root" in {
    val resolver = mock[PathResolver[String, FileType, FolderType]]
    val operation = mock[Operation[String]]
    val fs = createCryptFileSystem(optResolver = Some(resolver))
    when(fs.delegate.rootID).thenReturn(operation)

    fs.resolvePath("") should be(operation)
    verifyZeroInteractions(resolver)
  }

  it should "close the resolver in its close() implementation" in {
    val resolver = mock[PathResolver[String, FileType, FolderType]]
    val fs = createCryptFileSystem(optResolver = Some(resolver))

    fs.close()
    verify(resolver).close()
  }
}
