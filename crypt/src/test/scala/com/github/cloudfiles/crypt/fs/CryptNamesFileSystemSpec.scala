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
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper, Model}
import com.github.cloudfiles.crypt.alg.ShiftCryptAlgorithm
import com.github.cloudfiles.crypt.service.CryptService
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.security.SecureRandom

object CryptNamesFileSystemSpec {
  /**
   * Generates the ID of a test file based on the given index.
   *
   * @param idx the index
   * @return the ID of this test file
   */
  private def fileID(idx: Int): String = "file_" + idx

  /**
   * Generates the name of a file based on the given index.
   *
   * @param idx the index
   * @return the name of this test file
   */
  private def fileName(idx: Int): String = s"testFile$idx.txt"

  /**
   * Generates the ID of a test folder based on the given index.
   *
   * @param idx the index
   * @return the ID of this test folder
   */
  private def folderID(idx: Int): String = "folder_" + idx

  /**
   * Generates the name of a test folder based on the given index.
   *
   * @param idx the index
   * @return the name of this test folder
   */
  private def folderName(idx: Int): String = "testFolder" + idx

  /**
   * Initializes the given mock of a file system element to return the
   * properties specified.
   *
   * @param elem the mock for an element
   * @param id   the ID of this element
   * @param name the name of this element
   * @tparam A the type of the element
   * @return the initialized mock
   */
  private def initMock[A <: Model.Element[String]](elem: A, id: String, name: String): A = {
    when(elem.id).thenReturn(id)
    when(elem.name).thenReturn(name)
    elem
  }

  /**
   * Returns the encrypted form of the given name using the test algorithm.
   *
   * @param name the name to encrypt
   * @return the encrypted name
   */
  private def encryptName(name: String): String =
    CryptService.encodeBase64(ShiftCryptAlgorithm.encrypt(ByteString(name)))
}

/**
 * Test class for ''CryptNamesFileSystem''.
 */
class CryptNamesFileSystemSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar
  with AsyncTestHelper {

  import CryptFileSystemTestHelper._
  import com.github.cloudfiles.crypt.fs.CryptNamesFileSystemSpec._

  /**
   * Creates a new crypt file system instance for a test case.
   *
   * @return the new file system test instance
   */
  private def createCryptFileSystem(): CryptNamesFileSystem[String, FileType, FolderType] = {
    implicit val secRandom: SecureRandom = new SecureRandom
    val delegate = mock[ExtensibleFileSystem[String, FileType, FolderType, ContentType]]
    new CryptNamesFileSystem[String, FileType, FolderType](delegate, ShiftCryptAlgorithm,
      ShiftCryptAlgorithm.encryptKey, ShiftCryptAlgorithm.decryptKey)
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

  it should "resolve an empty path to the root folder ID" in {
    val fs = createCryptFileSystem()
    when(fs.delegate.rootID).thenReturn(stubOperation(FileID))

    runOp(testKit, fs.resolvePathComponents(Nil)) should be(FileID)
  }

  it should "resolve a path to an existing folder" in {
    val RootID = "theRootFolder"
    val components = Seq("the", "desired", "folder")
    val rootFolders = Map(folderID(1) -> createFolderMock(1, encryptName(folderName(1))),
      folderID(2) -> createFolderMock(2, encryptName(folderName(2))),
      folderID(3) -> createFolderMock(3, encryptName("the")))
    val rootContent = Model.FolderContent(RootID, Map.empty[String, FileType], rootFolders)
    val level1Folders = Map(folderID(4) -> createFolderMock(4, encryptName("desired")),
      folderID(5) -> createFolderMock(5, encryptName(folderName(5))))
    val level1Files = Map(fileID(1) -> createFileMock(1, encryptName(fileName(1))))
    val level1Content = Model.FolderContent(folderID(3), level1Files, level1Folders)
    val level2Folders = Map(folderID(6) -> createFolderMock(6, encryptName(folderName(6))),
      folderID(7) -> createFolderMock(7, encryptName(folderName(7))),
      folderID(8) -> createFolderMock(8, encryptName("folder")),
      folderID(9) -> createFolderMock(9, encryptName(folderName(9))))
    val level2Files = Map(fileID(2) -> createFileMock(2, encryptName(fileName(2))))
    val level2Content = Model.FolderContent(folderID(4), level2Files, level2Folders)
    val fs = createCryptFileSystem()
    when(fs.delegate.rootID).thenReturn(stubOperation(RootID))
    when(fs.delegate.folderContent(RootID)).thenReturn(stubOperation(rootContent))
    when(fs.delegate.folderContent(level1Content.folderID)).thenReturn(stubOperation(level1Content))
    when(fs.delegate.folderContent(level2Content.folderID)).thenReturn(stubOperation(level2Content))

    runOp(testKit, fs.resolvePathComponents(components)) should be(folderID(8))
  }

  it should "resolve a path to an existing file" in {
    val RootID = "theRootFolder"
    val Path = "/the/desired/test%20file.doc"
    val rootFolders = Map(folderID(1) -> createFolderMock(1, encryptName(folderName(1))),
      folderID(2) -> createFolderMock(2, encryptName(folderName(2))),
      folderID(3) -> createFolderMock(3, encryptName("the")))
    val rootContent = Model.FolderContent(RootID, Map.empty[String, FileType], rootFolders)
    val level1Folders = Map(folderID(4) -> createFolderMock(4, encryptName("desired")),
      folderID(5) -> createFolderMock(5, encryptName(folderName(5))))
    val level1Files = Map(fileID(1) -> createFileMock(1, encryptName(fileName(1))))
    val level1Content = Model.FolderContent(folderID(3), level1Files, level1Folders)
    val level2Folders = Map(folderID(6) -> createFolderMock(6, encryptName(folderName(6))))
    val level2Files = Map(fileID(2) -> createFileMock(2, encryptName(fileName(2))),
      fileID(3) -> createFileMock(3, encryptName("test file.doc")),
      fileID(4) -> createFileMock(4, encryptName(fileName(4))))
    val level2Content = Model.FolderContent(folderID(4), level2Files, level2Folders)
    val fs = createCryptFileSystem()
    when(fs.delegate.rootID).thenReturn(stubOperation(RootID))
    when(fs.delegate.folderContent(RootID)).thenReturn(stubOperation(rootContent))
    when(fs.delegate.folderContent(level1Content.folderID)).thenReturn(stubOperation(level1Content))
    when(fs.delegate.folderContent(level2Content.folderID)).thenReturn(stubOperation(level2Content))

    runOp(testKit, fs.resolvePath(Path)) should be(fileID(3))
  }

  it should "handle a path that cannot be resolved" in {
    val RootID = "theRootFolder"
    val components = Seq("the", "desired", "folder")
    val rootFolders = Map(folderID(1) -> createFolderMock(1, encryptName(folderName(1))),
      folderID(2) -> createFolderMock(2, encryptName(folderName(2))),
      folderID(3) -> createFolderMock(3, encryptName("the")))
    val rootFiles = Map(fileID(0) -> createFileMock(0, encryptName("the")))
    val rootContent = Model.FolderContent(RootID, rootFiles, rootFolders)
    val level1Folders = Map(folderID(4) -> createFolderMock(4, encryptName(folderName(4))),
      folderID(5) -> createFolderMock(5, encryptName(folderName(5))))
    val level1Files = Map(fileID(1) -> createFileMock(1, encryptName(fileName(1))),
      fileID(2) -> createFileMock(2, encryptName("desired")))
    val level1Content = Model.FolderContent(folderID(3), level1Files, level1Folders)
    val fs = createCryptFileSystem()
    when(fs.delegate.rootID).thenReturn(stubOperation(RootID))
    when(fs.delegate.folderContent(RootID)).thenReturn(stubOperation(rootContent))
    when(fs.delegate.folderContent(level1Content.folderID)).thenReturn(stubOperation(level1Content))

    val ex = expectFailedFuture[IllegalArgumentException](runOpFuture(testKit, fs.resolvePathComponents(components)))
    ex.getMessage should include(components.tail.toString())
  }
}
