/*
 * Copyright 2020-2025 The Developers Team.
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

import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.{FileTestHelper, Model}
import com.github.cloudfiles.crypt.alg.ShiftCryptAlgorithm
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito.{doReturn, verify, when}
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
 * Test class for ''CryptContentFileSystem''.
 */
class CryptContentFileSystemSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers
  with MockitoSugar {

  import CryptFileSystemTestHelper._

  /**
   * Creates a new crypt file system instance for a test case.
   *
   * @return the new file system test instance
   */
  private def createCryptFileSystem(): CryptContentFileSystem[String, FileType, FolderType] = {
    val delegate = mock[ExtensibleFileSystem[String, FileType, FolderType, ContentType]]
    new CryptContentFileSystem[String, FileType, FolderType](delegate, DefaultCryptConfig)
  }

  /**
   * Creates a mock for a file that is prepared to return some property values.
   *
   * @param id   the file ID
   * @param size the size
   * @return the file mock
   */
  private def fileMock(id: String, size: Long): FileType = {
    val file = mock[FileType]
    when(file.id).thenReturn(id)
    when(file.name).thenReturn(id + ".txt")
    when(file.size).thenReturn(size)
    file
  }

  "CryptContentFileSystem" should "resolve a file" in {
    val FileSize = 20210219210146L
    val CryptFileSize = ShiftCryptAlgorithm.decryptedSize(FileSize)
    val orgFile = fileMock("f", FileSize)
    val patchedFile = mock[FileType]
    val fs = createCryptFileSystem()
    when(fs.delegate.resolveFile(FileID)).thenReturn(stubOperation(orgFile))
    when(fs.delegate.patchFile(orgFile, ElementPatchSpec(patchSize = Some(CryptFileSize)))).thenReturn(patchedFile)

    runOpFuture(testKit, fs.resolveFile(FileID)) map (_ should be(patchedFile))
  }

  it should "return a patched content of a folder" in {
    val file1 = fileMock("f1", 20210220210141L)
    val file2 = fileMock("f2", 20210220210205L)
    val file1Patched = fileMock(file1.id, 42L)
    val file2Patched = fileMock(file2.id, 2048L)
    val folder = mock[FolderType]
    when(folder.id).thenReturn("someFolder")
    val content = Model.FolderContent(FileID,
      Map("f1" -> file1, "f2" -> file2),
      Map("folder" -> folder))
    val fs = createCryptFileSystem()
    when(fs.delegate.folderContent(FileID)).thenReturn(stubOperation(content))
    when(fs.delegate.patchFile(file1,
      ElementPatchSpec(patchSize = Some(ShiftCryptAlgorithm.decryptedSize(file1.size)))))
      .thenReturn(file1Patched)
    when(fs.delegate.patchFile(file2,
      ElementPatchSpec(patchSize = Some(ShiftCryptAlgorithm.decryptedSize(file2.size)))))
      .thenReturn(file2Patched)

    runOpFuture(testKit, fs.folderContent(FileID)) map { actContent =>
      actContent.folderID should be(content.folderID)
      actContent.folders should have size 1
      actContent.folders("folder") should be(folder)
      actContent.files should have size 2
      actContent.files("f1") should be(file1Patched)
      actContent.files("f2") should be(file2Patched)
    }
  }

  it should "decrypt file content on download" in {
    val cryptSource = Source(ShiftCryptAlgorithm.CipherText.grouped(16).toList)
    val cryptEntity = HttpEntity(ContentTypes.`application/octet-stream`, cryptSource)
    val operation = stubOperation(cryptEntity)
    val fs = createCryptFileSystem()
    doReturn(operation, null).when(fs.delegate).downloadFile(FileID)

    for {
      fileSource <- runOpFuture(testKit, fs.downloadFile(FileID))
      fileContent <- ShiftCryptAlgorithm.concatStream(fileSource.dataBytes)
    } yield fileContent.utf8String should be(FileTestHelper.TestData)
  }

  /**
   * Returns a source that emits the plain content of a test file.
   *
   * @return the source for the file's content
   */
  private def fileContentSource: Source[ByteString, NotUsed] =
    Source(ByteString(FileTestHelper.TestData).grouped(24).toList)

  /**
   * Checks whether the correct encrypted content of a file is uploaded.
   *
   * @param capSource the captor for the source
   * @return a ''Future'' with the test assertion
   */
  private def checkUploadSource(capSource: ArgumentCaptor[Source[ByteString, Any]]): Future[Assertion] = {
    ShiftCryptAlgorithm.concatStream(capSource.getValue) map { content =>
      content should be(ShiftCryptAlgorithm.CipherText)
    }
  }

  it should "encrypt the content of a file when it is created" in {
    val ParentID = "theParentFolder"
    val file = fileMock("irrelevantID", 20210221200640L)
    val patchedFile = fileMock(file.id, 1024)
    val fs = createCryptFileSystem()
    when(fs.patchFile(file, ElementPatchSpec(patchSize = Some(ShiftCryptAlgorithm.encryptedSize(file.size)))))
      .thenReturn(patchedFile)
    when(fs.delegate.createFile(argEq(ParentID), argEq(patchedFile), any())(any())).thenReturn(stubOperation(FileID))

    runOpFuture(testKit, fs.createFile(ParentID, file, fileContentSource)) flatMap { result =>
      result should be(FileID)
      val captSource = ArgumentCaptor.forClass(classOf[Source[ByteString, Any]])
      verify(fs.delegate).createFile(argEq(ParentID), argEq(patchedFile), captSource.capture())(any())
      checkUploadSource(captSource)
    }
  }

  it should "encrypt the content of a file when it is updated" in {
    val FileSize = 20210221203346L
    val CryptFileSize = ShiftCryptAlgorithm.encryptedSize(FileSize)
    val fs = createCryptFileSystem()
    when(fs.delegate.updateFileContent(argEq(FileID), argEq(CryptFileSize), any())(any()))
      .thenReturn(stubOperation(()))

    runOpFuture(testKit, fs.updateFileContent(FileID, FileSize, fileContentSource)) flatMap { _ =>
      val captSource = ArgumentCaptor.forClass(classOf[Source[ByteString, Any]])
      verify(fs.delegate).updateFileContent(argEq(FileID), argEq(CryptFileSize), captSource.capture())(any())
      checkUploadSource(captSource)
    }
  }
}
