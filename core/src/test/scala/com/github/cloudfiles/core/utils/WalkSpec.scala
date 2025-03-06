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

package com.github.cloudfiles.core.utils

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.{FileSystem, FileTestHelper, Model}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.Inspectors.forAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.Future

object WalkSpec {
  /** Type alias for the elements in the iteration. */
  type WalkItem = Model.Element[Path]

  /** Type alias for files in the iteration. */
  type WalkFile = Model.File[Path]

  /** Type alias for folders in the iteration. */
  type WalkFolder = Model.Folder[Path]

  /** Type alias for folder content objects. */
  type WalkFolderContent = Model.FolderContent[Path, WalkFile, WalkFolder]

  /**
   * The name of a folder which causes the test file system implementation to
   * throw an error. This is used for testing error handling.
   */
  private val ErrorFolderName = "error"

  /** The message of the test exception thrown for the error folder. */
  private val TestExceptionMessage = "Test exception: Processing of folder failed."

  /**
   * Creates a file element in the iteration from the given file.
   *
   * @param file the file
   * @return the corresponding file element
   */
  private def createFile(file: File): WalkFile = {
    val path = file.toPath
    val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
    new WalkFile {
      override def size: Long = file.length()

      override def id: Path = path

      override def name: String = file.getName

      override def description: Option[String] = None

      override def createdAt: Instant = attributes.creationTime().toInstant

      override def lastModifiedAt: Instant = attributes.lastModifiedTime().toInstant
    }
  }

  /**
   * Creates a folder element in the iteration from the given file.
   *
   * @param folder the folder
   * @return the corresponding folder element
   */
  private def createFolder(folder: File): WalkFolder = {
    val path = folder.toPath
    val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
    new WalkFolder {
      override def id: Path = path

      override def name: String = folder.getName

      override def description: Option[String] = None

      override def createdAt: Instant = attributes.creationTime().toInstant

      override def lastModifiedAt: Instant = attributes.lastModifiedTime().toInstant
    }
  }
}

/**
 * Test class for [[Walk]].
 *
 * This class tests the generic walk functionality by using a local file
 * system. Using this approach, it is quite easy to set up meaningful test
 * data.
 */
class WalkSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike with BeforeAndAfterAll
  with BeforeAndAfter with Matchers with FileTestHelper {
  def this() = this(ActorSystem("WalkSpec"))

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  after {
    tearDownTestFile()
  }

  import WalkSpec._

  /** The typed actor system in implicit scope. */
  private implicit val typedActorSystem: typed.ActorSystem[_] = system.toTyped

  /**
   * Creates a file with a given name in a given directory. The content of the
   * file is its name as string.
   *
   * @param dir  the directory
   * @param name the name of the file to be created
   * @return the path to the newly created file
   */
  private def createFile(dir: Path, name: String): Path =
    writeFileContent(dir resolve name, name)

  /**
   * Creates a directory below the given parent directory.
   *
   * @param parent the parent directory
   * @param name   the name of the new directory
   * @return the newly created directory
   */
  private def createDir(parent: Path, name: String): Path = {
    val dir = parent.resolve(name)
    Files.createDirectory(dir)
    dir
  }

  /**
   * Creates a directory structure with some test files and directories.
   *
   * @return a map with all directories and the files created in them
   */
  private def setUpDirectoryStructure(): Map[Path, Seq[Path]] = {
    val rootFiles = List(createFile(testDirectory, "test.txt"),
      createFile(testDirectory, "noMedium1.mp3"))
    val dir1 = createDir(testDirectory, "medium1")
    val dir2 = createDir(testDirectory, "medium2")
    val dir1Files = List(
      createFile(dir1, "noMedium2.mp3"),
      createFile(dir1, "README.TXT"),
      createFile(dir1, "medium1.settings"))
    val sub1 = createDir(dir1, "aSub1")
    val sub1Files = List(createFile(sub1, "medium1Song1.mp3"))
    val sub1Sub = createDir(sub1, "subSub")
    val sub1SubFiles = List(
      createFile(sub1Sub, "medium1Song2.mp3"),
      createFile(sub1Sub, "medium1Song3.mp3"))
    val sub2 = createDir(dir1, "anotherSub")
    val sub2Files = List(createFile(sub2, "song.mp3"))
    val dir2Files = List(
      createFile(dir2, "medium2Song1.mp3"),
      createFile(dir2, "medium2Song2.mp3")
    )
    val sub3 = createDir(dir2, "medium2Sub")
    val sub3Files = List(createFile(sub3, "medium2Song3.mp3"))

    Map(testDirectory -> rootFiles, dir1 -> dir1Files, sub1 -> sub1Files, sub1Sub ->
      sub1SubFiles, sub2 -> sub2Files, dir2 -> dir2Files, sub3 -> sub3Files)
  }

  /**
   * Returns a sink for collecting the items produced by the test source.
   *
   * @return the sink
   */
  private def foldSink(): Sink[WalkItem, Future[List[WalkItem]]] =
    Sink.fold[List[WalkItem], WalkItem](List.empty[WalkItem])((lst, p) => p :: lst)

  /**
   * Runs a stream with the given source and returns a future with the 
   * collected items.
   *
   * @param source the source to be tested
   * @return a [[Future]] with the data obtained from the source
   */
  private def runSource(source: Source[WalkItem, NotUsed]): Future[List[WalkItem]] =
    source.runWith(foldSink()).map(_.reverse)

  /**
   * Creates a [[FileSystem]] that can be used for testing the walk
   * functionality. The implementation created here only defines the bare
   * minimum of operations.
   *
   * @return the [[FileSystem]] to be used for tests
   */
  private def createFileSystem(): FileSystem[Path, WalkFile, WalkFolder, WalkFolderContent] =
    new FileSystem[Path, WalkFile, WalkFolder, WalkFolderContent] {
      override def resolvePath(path: String)(implicit system: typed.ActorSystem[_]): FileSystem.Operation[Path] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def rootID(implicit system: typed.ActorSystem[_]): FileSystem.Operation[Path] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def resolveFile(id: Path)(implicit system: typed.ActorSystem[_]): FileSystem.Operation[WalkFile] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def resolveFolder(id: Path)(implicit system: typed.ActorSystem[_]): FileSystem.Operation[WalkFolder] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def folderContent(id: Path)
                                (implicit system: typed.ActorSystem[_]): FileSystem.Operation[WalkFolderContent] =
        Operation { _ =>
          Future {
            if (id.getFileName.toString == ErrorFolderName) {
              throw new IllegalStateException(TestExceptionMessage)
            }

            val children = id.toFile.listFiles()
            val (subFiles, subFolders) = children.partition(_.isFile)
            val contentFiles = subFiles.map { f =>
              f.toPath -> WalkSpec.createFile(f)
            }.toMap
            val contentFolders = subFolders.map { f =>
              f.toPath -> WalkSpec.createFolder(f)
            }.toMap
            Model.FolderContent(id, contentFiles, contentFolders)
          }(system.executionContext)
        }

      override def createFolder(parent: Path, folder: Model.Folder[Path])
                               (implicit system: typed.ActorSystem[_]): FileSystem.Operation[Path] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def updateFolder(folder: Model.Folder[Path])
                               (implicit system: typed.ActorSystem[_]): FileSystem.Operation[Unit] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def deleteFolder(folderID: Path)(implicit system: typed.ActorSystem[_]): FileSystem.Operation[Unit] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def createFile(parent: Path, file: Model.File[Path], content: Source[ByteString, Any])
                             (implicit system: typed.ActorSystem[_]): FileSystem.Operation[Path] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def updateFile(file: Model.File[Path])
                             (implicit system: typed.ActorSystem[_]): FileSystem.Operation[Unit] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def updateFileContent(fileID: Path, size: Long, content: Source[ByteString, Any])
                                    (implicit system: typed.ActorSystem[_]): FileSystem.Operation[Unit] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def downloadFile(fileID: Path)
                               (implicit system: typed.ActorSystem[_]): FileSystem.Operation[HttpEntity] =
        throw new UnsupportedOperationException("Unexpected invocation.")

      override def deleteFile(fileID: Path)(implicit system: typed.ActorSystem[_]): FileSystem.Operation[Unit] =
        throw new UnsupportedOperationException("Unexpected invocation.")
    }

  "Walk" should "return all files in the scanned BFS directory structure" in {
    val fileData = setUpDirectoryStructure()
    val allFiles = fileData.values.flatten.toSeq

    val source = Walk.bfsSource(createFileSystem(), null, testDirectory)
    runSource(source).map(elements => elements.filter(_.isInstanceOf[WalkFile])).map { files =>
      files map (_.id) should contain theSameElementsAs allFiles
    }
  }

  it should "return all folders in the scanned BFS directory structure" in {
    val fileData = setUpDirectoryStructure()
    val expectedFolders = fileData.keySet - testDirectory

    val source = Walk.bfsSource(createFileSystem(), null, testDirectory)
    runSource(source).map(elements => elements.filter(_.isInstanceOf[WalkFolder])).map { folders =>
      folders map (_.id) should contain theSameElementsAs expectedFolders
    }
  }

  it should "correctly resolve a larger number of folders" in {
    val FolderCount = 32
    val root = Files.createDirectory(testDirectory.resolve("iterationRoot"))
    (1 to FolderCount).foreach { idx =>
      val folder = Files.createDirectory(root.resolve(s"sub$idx"))
      writeFileContent(folder.resolve(s"testFile$idx.txt"), s"This is test file $idx.")
    }
    val expectedNames = (1 to FolderCount).flatMap { idx => List(s"sub$idx", s"testFile$idx.txt") }

    val source = Walk.bfsSource(createFileSystem(), null, testDirectory)

    runSource(source).map { elements =>
      val names = elements.map(_.name)
      names should contain allElementsOf expectedNames
    }
  }

  it should "support an empty iteration in BFS order" in {
    val source = Walk.bfsSource(createFileSystem(), null, testDirectory)

    runSource(source).map { elements =>
      elements shouldBe empty
    }
  }

  it should "support iteration in BFS order" in {
    @tailrec def calcLevel(p: Path, dist: Int): Int =
      if (testDirectory == p) dist
      else calcLevel(p.getParent, dist + 1)

    def level(p: Path): Int = calcLevel(p, 0)

    setUpDirectoryStructure()
    val source = Walk.bfsSource(createFileSystem(), null, testDirectory)

    runSource(source) map { paths =>
      val pathLevels = paths map (d => level(d.id))
      val compareLevels = pathLevels.drop(1) :+ Int.MaxValue

      forAll(pathLevels.zip(compareLevels)) { t => t._1 should be <= t._2 }
    }
  }

  it should "return all files in the scanned DFS directory structure" in {
    val fileData = setUpDirectoryStructure()
    val allFiles = fileData.values.flatten.toSeq

    val source = Walk.dfsSource(createFileSystem(), null, testDirectory)
    runSource(source).map(elements => elements.filter(_.isInstanceOf[WalkFile])).map { files =>
      files map (_.id) should contain theSameElementsAs allFiles
    }
  }

  it should "return all folders in the scanned DFS directory structure" in {
    val fileData = setUpDirectoryStructure()
    val expectedFolders = fileData.keySet - testDirectory

    val source = Walk.dfsSource(createFileSystem(), null, testDirectory)
    runSource(source).map(elements => elements.filter(_.isInstanceOf[WalkFolder])).map { folders =>
      folders map (_.id) should contain theSameElementsAs expectedFolders
    }
  }

  it should "process the files of a directory before sub dirs in DFS mode" in {
    def indexOfFile(files: Seq[WalkItem], name: String): Int =
      files.indexWhere(_.id.toString endsWith name)

    setUpDirectoryStructure()
    val source = Walk.dfsSource(createFileSystem(), null, testDirectory)

    runSource(source).map(elements => elements.filter(_.isInstanceOf[WalkFile])).map { files =>
      val idxSettings = indexOfFile(files, "medium1.settings")
      val idxSong = indexOfFile(files, "medium1Song1.mp3")
      idxSettings should be < idxSong
    }
  }

  it should "support an empty iteration in DFS order" in {
    val source = Walk.dfsSource(createFileSystem(), null, testDirectory)

    runSource(source).map { elements =>
      elements shouldBe empty
    }
  }

  it should "support iteration in DFS order" in {
    @tailrec def mapToParent(p: Path): Int =
      if (testDirectory == p) 0
      else if ("medium1" == p.getFileName.toString) 1
      else if ("medium2" == p.getFileName.toString) 2
      else mapToParent(p.getParent)

    def filterByParent(p: Path): Boolean = {
      val medium = mapToParent(p)
      medium == 1 || medium == 2
    }

    setUpDirectoryStructure()
    val source = Walk.dfsSource(createFileSystem(), null, testDirectory)

    runSource(source).map(elements => elements.filter(_.isInstanceOf[WalkFile])).map { files =>
      val parentIndices = files.map(_.id)
        .filter(filterByParent)
        .map(mapToParent)
      val (parentChanges, _) = parentIndices.foldLeft((0, 0)) { (s, e) =>
        if (s._2 == e) s else (s._1 + 1, e)
      }
      // all elements under a given parent should be listed in a series
      parentChanges should be(2)
    }
  }

  it should "fail the source if there is an error when executing a file system operation" in {
    val folders = setUpDirectoryStructure().keySet
    Files.createDirectory(folders.last.resolve(ErrorFolderName))
    val source = Walk.bfsSource(createFileSystem(), null, testDirectory)

    recoverToExceptionIf[IllegalStateException] {
      runSource(source)
    }.map(_.getMessage should be(TestExceptionMessage))
  }
}
