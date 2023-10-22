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

package com.github.cloudfiles.crypt.fs.resolver

import com.github.cloudfiles.core.{FileSystem, Model}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
 * Test class for ''PathComponentResolver''.
 */
class PathComponentsResolverSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar {

  import com.github.cloudfiles.crypt.fs.CryptFileSystemTestHelper._

  /**
   * Creates a mock for a file system, which can be passed to a test resolver
   * instance.
   *
   * @return the mock file system
   */
  private def createFileSystemMock(): FileSystem[String, FileType, FolderType,
    Model.FolderContent[String, FileType, FolderType]] =
    mock[FileSystem[String, FileType, FolderType, Model.FolderContent[String, FileType, FolderType]]]

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

  /**
   * Creates a test resolver instance.
   *
   * @return the test resolver
   */
  private def createResolver(): PathComponentsResolver[String, FileType, FolderType] =
    new PathComponentsResolver[String, FileType, FolderType]

  "PathComponentResolver" should "resolve an empty path to the root folder ID" in {
    val fs = createFileSystemMock()
    when(fs.rootID).thenReturn(stubOperation(FileID))
    val resolver = createResolver()

    runOp(testKit, resolver.resolve(Nil, fs, DefaultCryptConfig)) should be(FileID)
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
    val fs = createFileSystemMock()
    when(fs.rootID).thenReturn(stubOperation(RootID))
    when(fs.folderContent(RootID)).thenReturn(stubOperation(rootContent))
    when(fs.folderContent(level1Content.folderID)).thenReturn(stubOperation(level1Content))
    when(fs.folderContent(level2Content.folderID)).thenReturn(stubOperation(level2Content))
    val resolver = createResolver()

    runOp(testKit, resolver.resolve(components, fs, DefaultCryptConfig)) should be(folderID(8))
  }

  it should "resolve a path to an existing file" in {
    val RootID = "theRootFolder"
    val components = Seq("the", "desired", "test file.doc")
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
    val fs = createFileSystemMock()
    when(fs.rootID).thenReturn(stubOperation(RootID))
    when(fs.folderContent(RootID)).thenReturn(stubOperation(rootContent))
    when(fs.folderContent(level1Content.folderID)).thenReturn(stubOperation(level1Content))
    when(fs.folderContent(level2Content.folderID)).thenReturn(stubOperation(level2Content))
    val resolver = createResolver()

    runOp(testKit, resolver.resolve(components, fs, DefaultCryptConfig)) should be(fileID(3))
  }

  it should "ignore invalid element names when resolving a path" in {
    val RootID = "rootFolderOfStrangeContent"
    val components = Seq("the", "file.doc")
    val rootFolders = Map(folderID(1) -> createFolderMock(1, encryptName(folderName(1))),
      folderID(2) -> createFolderMock(2, "not.encrypted"),
      folderID(3) -> createFolderMock(3, encryptName("the")))
    val rootContent = Model.FolderContent(RootID, Map.empty[String, FileType], rootFolders)
    val level1Folders = Map(folderID(4) -> createFolderMock(4, encryptName(folderName(4))))
    val level1Files = Map(fileID(1) -> createFileMock(1, encryptName("file.doc")))
    val level1Content = Model.FolderContent(folderID(3), level1Files, level1Folders)
    val fs = createFileSystemMock()
    when(fs.rootID).thenReturn(stubOperation(RootID))
    when(fs.folderContent(RootID)).thenReturn(stubOperation(rootContent))
    when(fs.folderContent(level1Content.folderID)).thenReturn(stubOperation(level1Content))
    val resolver = createResolver()

    runOp(testKit, resolver.resolve(components, fs, DefaultCryptConfig)) should be(fileID(1))
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
    val fs = createFileSystemMock()
    when(fs.rootID).thenReturn(stubOperation(RootID))
    when(fs.folderContent(RootID)).thenReturn(stubOperation(rootContent))
    when(fs.folderContent(level1Content.folderID)).thenReturn(stubOperation(level1Content))
    val resolver = createResolver()

    val ex = expectFailedFuture[IllegalArgumentException](runOpFuture(testKit,
      resolver.resolve(components, fs, DefaultCryptConfig)))
    ex.getMessage should include(components.tail.toString())
  }

  it should "have an empty close() function" in {
    val resolver = createResolver()

    // We can only test that no exception is thrown.
    resolver.close()
  }
}
