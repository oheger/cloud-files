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

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.delegate.ElementPatchSpec
import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper, Model}
import com.github.cloudfiles.localfs.LocalFileSystemSpec.{RootFolder, fileContentSource}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.attribute.FileTime
import java.nio.file.{FileSystemException, Files, Path}
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

object LocalFileSystemSpec {
  /** The name of the root folder for the local file system. */
  private val RootFolder = "localRoot"

  /**
   * Returns a ''Source'' to define the content of a test file.
   *
   * @return the source with the content of the file
   */
  private def fileContentSource: Source[ByteString, Any] = Source.single(ByteString(FileTestHelper.testBytes()))
}

/**
 * Test class for ''LocalFileSystem''.
 */
class LocalFileSystemSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with BeforeAndAfterEach
  with Matchers with MockitoSugar with FileTestHelper with AsyncTestHelper {
  override protected def afterEach(): Unit = {
    tearDownTestFile()
    super.afterEach()
  }

  /**
   * Returns the root path of the test file system. This is a sub folder of the
   * managed test directory.
   *
   * @return the root path of the test file system
   */
  def rootPath: Path = testDirectory.resolve(RootFolder)

  /**
   * Creates a configuration object for a test file system with default
   * settings.
   *
   * @return the test configuration
   */
  private def createConfig(): LocalFsConfig = {
    Files.createDirectory(rootPath)
    LocalFsConfig(rootPath, implicitly[ExecutionContext])
  }

  /**
   * Executes the given operation. As operations for the local file system do
   * not require an actor, a '''null''' reference is passed in.
   *
   * @param op the operation
   * @tparam A the result type of the operation
   * @return the result of the operation
   */
  private def run[A](op: Operation[A]): A = futureResult(op.run(null))

  /**
   * Executes the given operation and expects it to fail with a
   * ''FileSystemException''.
   *
   * @param op the operation
   * @return the exception thrown by the operation
   */
  private def failedRun(op: Operation[_]): FileSystemException =
    expectFailedFuture[FileSystemException](op.run(null))

  /**
   * Creates a file system with the given config to be used by tests.
   *
   * @param config the configuration to use
   * @return the test file system
   */
  private def createFileSystem(config: LocalFsConfig = createConfig()): LocalFileSystem = new LocalFileSystem(config)

  "LocalFileSystem" should "return the root ID" in {
    val fs = createFileSystem()

    val root = run(fs.rootID)
    root should be(rootPath)
  }

  it should "resolve a path" in {
    val fs = createFileSystem()

    val resolvedPath = run(fs.resolvePath("test/folder/path"))
    resolvedPath should be(rootPath.resolve("test").resolve("folder").resolve("path"))
  }

  it should "resolve a path that starts with a separator" in {
    val SubPath = "theSubPath"
    val fs = createFileSystem()

    val resolvedPath = run(fs.resolvePath("/" + SubPath))
    resolvedPath should be(rootPath.resolve(SubPath))
  }

  it should "decode the components of a path when it is resolved" in {
    val path = "test/the%20data/My%20test%20file.xml"
    val expResolvedPath = rootPath.resolve("test/the data/My test file.xml")
    val fs = createFileSystem()

    val resolvedPath = run(fs.resolvePath(path))
    resolvedPath should be(expResolvedPath)
  }

  it should "delete a folder" in {
    val fs = createFileSystem()
    val subFolder = Files.createDirectory(rootPath.resolve("toDelete"))

    run(fs.deleteFolder(subFolder))
    Files.exists(subFolder) shouldBe false
  }

  it should "delete a file" in {
    val fs = createFileSystem()
    val file = writeFileContent(rootPath.resolve("toDelete.txt"), "toDelete")

    run(fs.deleteFile(file))
    Files.exists(file) shouldBe false
  }

  it should "resolve a folder" in {
    val FolderName = "test-folder"
    val fs = createFileSystem()
    val testFolder = Files.createDirectory(rootPath.resolve(FolderName))

    val folder = run(fs.resolveFolder(testFolder))
    folder.id should be(testFolder)
    folder.name should be(FolderName)
    folder.description should be(None)
    folder.createdAt should be(Files.getAttribute(testFolder, "creationTime").asInstanceOf[FileTime].toInstant)
    folder.lastModifiedAt should be(Files.getLastModifiedTime(testFolder).toInstant)
    folder.lastModifiedUpdate should be(None)
  }

  it should "resolve a file" in {
    val FileName = "testFileWithAttributes.dat"
    val fs = createFileSystem()
    val filePath = writeFileContent(rootPath.resolve(FileName), FileTestHelper.TestData)

    val file = run(fs.resolveFile(filePath))
    file.id should be(filePath)
    file.name should be(FileName)
    file.description should be(None)
    file.createdAt should be(Files.getAttribute(filePath, "creationTime").asInstanceOf[FileTime].toInstant)
    file.lastModifiedAt should be(Files.getLastModifiedTime(filePath).toInstant)
    file.size should be(FileTestHelper.TestData.length)
    file.lastModifiedUpdate should be(None)
  }

  it should "fail to resolve a folder which is actually a file" in {
    val fs = createFileSystem()
    val path = writeFileContent(rootPath.resolve("plainFile.txt"), "not a folder")

    val exception = failedRun(fs.resolveFolder(path))
    exception.getMessage should include(path.toString)
  }

  it should "fail to resolve a file which is actually a folder" in {
    val fs = createFileSystem()
    val path = Files.createDirectory(rootPath.resolve("aFolder"))

    val exception = failedRun(fs.resolveFile(path))
    exception.getMessage should include(path.toString)
  }

  it should "download the content of a file" in {
    val fs = createFileSystem()
    val file = writeFileContent(rootPath.resolve("someData.txt"), FileTestHelper.TestData)

    val content = run(fs.downloadFile(file))
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val mat = Materializer(testKit.system)
    val data = futureResult(content.dataBytes.runWith(sink)(mat)).utf8String
    data should be(FileTestHelper.TestData)
  }

  it should "create a new folder" in {
    val FolderName = "newFolder"
    val fs = createFileSystem()
    val folder = LocalFsModel.newFolder(name = FolderName)

    val path = run(fs.createFolder(rootPath, folder))
    path should be(rootPath.resolve(FolderName))
    Files.isDirectory(path) shouldBe true
  }

  it should "create a new folder and set the last modified date" in {
    val FolderName = "newFolderWithModifiedDate"
    val ModifiedAt = Instant.parse("2021-05-23T16:25:43.000Z")
    val fs = createFileSystem()
    val folder = LocalFsModel.newFolder(name = FolderName, lastModifiedAt = Some(ModifiedAt))

    val path = run(fs.createFolder(rootPath, folder))
    Files.getLastModifiedTime(path).toInstant should be(ModifiedAt)
  }

  it should "create a new folder if another folder type" in {
    val FolderName = "newFolderAlternative"
    val fs = createFileSystem()
    val folder = mock[Model.Folder[Path]]
    when(folder.name).thenReturn(FolderName)

    val path = run(fs.createFolder(rootPath, folder))
    path should be(rootPath.resolve(FolderName))
    Files.isDirectory(path) shouldBe true
  }

  it should "update the properties of a folder" in {
    val ModifiedAt = Instant.parse("2021-05-23T18:33:20.000Z")
    val fs = createFileSystem()
    val folderPath = Files.createDirectory(rootPath.resolve("updateFolder"))
    val folder = LocalFsModel.newFolder(folderPath, lastModifiedAt = Some(ModifiedAt))

    run(fs.updateFolder(folder))
    Files.getLastModifiedTime(folderPath).toInstant should be(ModifiedAt)
  }

  it should "ignore a folder update operation if there is nothing to change" in {
    val fs = createFileSystem()
    val folderPath = rootPath.resolve("anotherUpdateFolder")
    val folder = mock[Model.Folder[Path]]
    when(folder.id).thenReturn(folderPath)

    run(fs.updateFolder(folder)) // can only check that no exception is thrown
  }

  it should "create a new file" in {
    val FileName = "newFile.dat"
    val fs = createFileSystem()
    val file = LocalFsModel.newFile(name = FileName)

    val path = run(fs.createFile(rootPath, file, fileContentSource))
    path.getFileName.toString should be(FileName)
    path.getParent should be(rootPath)
    readDataFile(path) should be(FileTestHelper.TestData)
  }

  it should "create a new file with properties" in {
    val FileName = "newFileWithProperties.dat"
    val ModifiedAt = Instant.parse("2021-05-23T18:49:32.000Z")
    val fs = createFileSystem()
    val file = LocalFsModel.newFile(name = FileName, lastModifiedAt = Some(ModifiedAt))

    val path = run(fs.createFile(rootPath, file, fileContentSource))
    Files.getLastModifiedTime(path).toInstant should be(ModifiedAt)
  }

  it should "update properties of a file" in {
    val Content = "the content"
    val ModifiedAt = Instant.parse("2021-05-23T18:56:05.000Z")
    val fs = createFileSystem()
    val filePath = writeFileContent(rootPath.resolve("updateFile.txt"), Content)
    val file = LocalFsModel.newFile(filePath, lastModifiedAt = Some(ModifiedAt))

    run(fs.updateFile(file))
    Files.getLastModifiedTime(filePath).toInstant should be(ModifiedAt)
    readDataFile(filePath) should be(Content)
  }

  it should "update the content of a file" in {
    val fs = createFileSystem()
    val filePath = writeFileContent(rootPath.resolve("updateFileContent.txt"),
      FileTestHelper.TestData + "overwritten")

    run(fs.updateFileContent(filePath, 0, fileContentSource))
    readDataFile(filePath) should be(FileTestHelper.TestData)
  }

  it should "patch a folder with an empty patch specification" in {
    val FolderName = "aFolder"
    val CreatedAt = Instant.parse("2021-05-23T19:13:15.000Z")
    val ModifiedAt = Instant.parse("2021-05-23T19:13:30.000Z")
    val fs = createFileSystem()
    val folderPath = rootPath.resolve(FolderName)
    val folder = mock[Model.Folder[Path]]
    when(folder.id).thenReturn(folderPath)
    when(folder.name).thenReturn(FolderName)
    when(folder.createdAt).thenReturn(CreatedAt)
    when(folder.lastModifiedAt).thenReturn(ModifiedAt)
    val expFolder = LocalFsModel.LocalFolder(id = folderPath, name = FolderName, createdAt = CreatedAt,
      lastModifiedAt = ModifiedAt)

    val patchedFolder = fs.patchFolder(folder, ElementPatchSpec())
    patchedFolder should be(expFolder)
  }

  it should "apply a patch specification to a folder" in {
    val NewName = "patchedFolderName"
    val CreatedAt = Instant.parse("2021-05-23T19:19:40.000Z")
    val ModifiedAt = Instant.parse("2021-05-23T19:19:49.000Z")
    val ModifiedUpdate = ModifiedAt.plusSeconds(42)
    val fs = createFileSystem()
    val folderPath = rootPath.resolve(NewName)
    val sourceFolder = LocalFsModel.LocalFolder(id = folderPath, name = "originalName", createdAt = CreatedAt,
      lastModifiedAt = ModifiedAt, lastModifiedUpdate = Some(ModifiedUpdate))
    val spec = ElementPatchSpec(patchName = Some(NewName), patchSize = Some(128))
    val expFolder = LocalFsModel.LocalFolder(id = folderPath, name = NewName, createdAt = CreatedAt,
      lastModifiedAt = ModifiedAt, lastModifiedUpdate = Some(ModifiedUpdate))

    val patchedFolder = fs.patchFolder(sourceFolder, spec)
    patchedFolder should be(expFolder)
  }

  it should "patch a file with an empty path specification" in {
    val FileName = "aFile.dat"
    val CreatedAt = Instant.parse("2021-05-23T19:33:30.000Z")
    val ModifiedAt = Instant.parse("2021-05-23T19:33:38.000Z")
    val Size = 645237
    val fs = createFileSystem()
    val filePath = rootPath.resolve(FileName)
    val file = mock[Model.File[Path]]
    when(file.id).thenReturn(filePath)
    when(file.name).thenReturn(FileName)
    when(file.createdAt).thenReturn(CreatedAt)
    when(file.lastModifiedAt).thenReturn(ModifiedAt)
    when(file.size).thenReturn(Size)
    val expFile = LocalFsModel.LocalFile(id = filePath, name = FileName, createdAt = CreatedAt,
      lastModifiedAt = ModifiedAt, size = Size)

    val patchedFile = fs.patchFile(file, ElementPatchSpec())
    patchedFile should be(expFile)
  }

  it should "apply a patch specification to a file" in {
    val NewName = "patchedFileName.doc"
    val CreatedAt = Instant.parse("2021-05-23T19:36:25.000Z")
    val ModifiedAt = Instant.parse("2021-05-23T19:36:33.000Z")
    val ModifiedUpdate = ModifiedAt.plusSeconds(72)
    val NewSize = 122354
    val fs = createFileSystem()
    val filePath = rootPath.resolve(NewName)
    val sourceFile = LocalFsModel.LocalFile(id = filePath, name = "originalName", createdAt = CreatedAt,
      lastModifiedAt = ModifiedAt, size = NewSize / 2, lastModifiedUpdate = Some(ModifiedUpdate))
    val spec = ElementPatchSpec(patchName = Some(NewName), patchSize = Some(NewSize))
    val expFile = LocalFsModel.LocalFile(id = filePath, name = NewName, createdAt = CreatedAt,
      lastModifiedAt = ModifiedAt, size = NewSize, lastModifiedUpdate = Some(ModifiedUpdate))

    val patchedFile = fs.patchFile(sourceFile, spec)
    patchedFile should be(expFile)
  }

  it should "return the content of a folder" in {
    val fs = createFileSystem()
    val subPath = Files.createDirectory(rootPath.resolve("subPath"))
    val file1 = writeFileContent(subPath.resolve("file1.txt"), "content of file1")
    val file2 = writeFileContent(subPath.resolve("another file.doc"), "Content of another file.")
    val file3 = writeFileContent(subPath.resolve("oneMoreFile.dat"), "And one more.")
    val subSubPath = Files.createDirectory(subPath.resolve("subSub"))
    writeFileContent(subSubPath.resolve("subFile"), "Should be ignored.")

    val content = run(fs.folderContent(subPath))
    content.folderID should be(subPath)
    content.files.keys should contain only(file1, file2, file3)
    content.folders.keys should contain only subSubPath
    val file = content.files(file1)
    file.name should be("file1.txt")
    file.size should be(16)
    content.files(file2).name should be("another file.doc")
    content.folders(subSubPath).name should be("subSub")
  }

  it should "fail to resolve a path outside of the directory structure" in {
    val fs = createFileSystem()
    val InvalidPath = "../sibling"

    val exception = failedRun(fs.resolvePath(InvalidPath))
    exception.getMessage should include(s"not a sub path of '${fs.config.basePath}'.")
    exception.getMessage should include("sibling")
  }

  it should "fail to resolve a folder outside of the directory structure" in {
    val fs = createFileSystem()

    failedRun(fs.resolveFolder(testDirectory))
  }

  it should "normalize paths when checking them" in {
    val fs = createFileSystem()
    val invalidPath = rootPath.resolve("../")

    failedRun(fs.resolveFolder(invalidPath))
  }

  it should "fail to resolve a file outside of the directory structure" in {
    val fileOutside = createDataFile("outside")
    val fs = createFileSystem()

    failedRun(fs.resolveFile(fileOutside))
  }

  it should "fail to read the content of a folder outside of the directory structure" in {
    val fs = createFileSystem()

    failedRun(fs.folderContent(testDirectory))
  }

  it should "fail to create a folder outside of the directory structure" in {
    val fs = createFileSystem()

    failedRun(fs.createFolder(rootPath, LocalFsModel.newFolder(name = "../out")))
  }

  it should "fail to update a folder outside of the directory structure" in {
    val fs = createFileSystem()

    failedRun(fs.updateFolder(LocalFsModel.newFolder(testDirectory)))
  }

  it should "fail to delete a folder outside of the directory structure" in {
    val fs = createFileSystem()
    val invalidPath = Files.createDirectory(testDirectory.resolve("outside"))

    failedRun(fs.deleteFolder(invalidPath))
    Files.exists(invalidPath) shouldBe true
  }

  it should "fail to delete a file outside of the directory structure" in {
    val fs = createFileSystem()
    val invalidFile = createDataFile("must not be deleted")

    failedRun(fs.deleteFile(invalidFile))
    Files.exists(invalidFile) shouldBe true
  }

  it should "fail to create a file outside the directory structure" in {
    val fs = createFileSystem()
    val invalidFile = LocalFsModel.newFile(name = "../outside.txt")

    failedRun(fs.createFile(rootPath, invalidFile, fileContentSource))
    Files.exists(testDirectory.resolve("outside.txt")) shouldBe false
  }

  it should "fail to update the content of a file outside the directory structure" in {
    val Content = "must not be overwritten"
    val fs = createFileSystem()
    val invalidFile = createDataFile(Content)

    failedRun(fs.updateFileContent(invalidFile, 0, fileContentSource))
    readDataFile(invalidFile) should be(Content)
  }

  it should "fail to update a file's properties outside of the directory structure" in {
    val fs = createFileSystem()
    val invalidFile = createDataFile("no properties change")
    val modifiedTime = Files.getLastModifiedTime(invalidFile)
    val updateFile = LocalFsModel.newFile(invalidFile,
      lastModifiedAt = Some(Instant.parse("2021-05-25T20:16:04.000Z")))

    failedRun(fs.updateFile(updateFile))
    Files.getLastModifiedTime(invalidFile) should be(modifiedTime)
  }

  it should "fail to download a file outside of the directory structure" in {
    val fs = createFileSystem()
    val invalidFile = createDataFile("not downloaded")

    failedRun(fs.downloadFile(invalidFile))
  }

  it should "support disabling path checks" in {
    val config = createConfig().copy(sanitizePaths = false)
    val fs = createFileSystem(config)
    val outsideFile = createDataFile("should be deleted")

    run(fs.deleteFile(outsideFile))
    Files.exists(outsideFile) shouldBe false
  }
}
