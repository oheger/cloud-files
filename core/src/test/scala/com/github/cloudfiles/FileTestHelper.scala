/*
 * Copyright 2020 The Developers Team.
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

package com.github.cloudfiles

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, Paths, SimpleFileVisitor}
import scala.io.Source

object FileTestHelper {
  /** A string with defined test data. */
  val TestData: String =
    """|Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy
       |eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam
       |voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita
       |kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem
       |ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod
       |tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At
       |vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd
       |gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem ipsum
       |dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor
       |invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero
       |eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no
       |sea takimata sanctus est Lorem ipsum dolor sit amet.""".stripMargin

  /** The prefix for temporary files created by this trait. */
  val TempFilePrefix = "FileTestHelper"

  /** The suffix for temporary files created by this trait. */
  val TempFileSuffix = ".tmp"

  /** Constant for the line separator string. */
  val NL: String = System.getProperty("line.separator")

  /**
   * A string with test data that does not contain line feeds. Line feeds are
   * sometimes problematic, e.g. when reading or writing files over different
   * operating systems.
   */
  lazy val TestDataSingleLine: String = TestData.replace(NL, " ")

  /**
   * Helper method for converting a string to a byte array.
   *
   * @param s the string
   * @return the byte array
   */
  def toBytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  /**
   * Returns a byte array with the complete test data.
   *
   * @return the test bytes
   */
  def testBytes(): Array[Byte] = toBytes(TestData)
}

/**
 * A helper trait which can be used by test classes that need access to a file
 * with defined test data.
 *
 * This trait allows creating a test file. The file can be either be filled
 * initially with test data (for tests of reading functionality) or be read
 * later (for tests of writing functionality). There is also a cleanup method
 * for removing the file when it is no longer needed.
 */
trait FileTestHelper {

  import FileTestHelper._

  /** The test file managed by this trait. */
  var optTestDirectory: Option[Path] = None

  /**
   * Removes the temporary file if it exists.
   */
  def tearDownTestFile(): Unit = {
    optTestDirectory foreach deleteTree
    optTestDirectory = None
  }

  /**
   * Returns the path to the temporary directory managed by this trait.
   *
   * @return the path to the managed directory
   */
  def testDirectory: Path = ensureTempDirectory()

  /**
   * Creates a new temporary file reference with no content.
   *
   * @return the path to the new file
   */
  def createFileReference(): Path =
    Files.createTempFile(ensureTempDirectory(), TempFilePrefix, TempFileSuffix)

  /**
   * Writes the given content in a file specified by the given path.
   *
   * @param path    the path of the file
   * @param content the content to be written
   * @return the path to the file that was written
   */
  def writeFileContent(path: Path, content: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, toBytes(content))
    path
  }

  /**
   * Creates a new temporary file physically on disk which has the specified content.
   *
   * @param content the content of the file
   * @return the path to the new file
   */
  def createDataFile(content: String = FileTestHelper.TestData): Path = {
    val path = createFileReference()
    writeFileContent(path, content)
  }

  /**
   * Creates a path with the given name in the temporary directory managed by
   * this trait. No file is created physically.
   *
   * @param name the name of the path to be created
   * @return the newly created path
   */
  def createPathInDirectory(name: String): Path =
    ensureTempDirectory() resolve name

  /**
   * Reads the content of the specified data file and returns it as a string.
   *
   * @param path the path to the file to be read
   * @return the content read from the data file
   */
  def readDataFile(path: Path): String = {
    val source = Source.fromFile(path.toFile)
    val result = source.getLines().mkString(NL)
    source.close()
    result
  }

  /**
   * Returns a set with all paths that are found in the managed temporary
   * directory.
   *
   * @return a set with all encountered paths (excluding the root directory)
   */
  def listManagedDirectory(): collection.mutable.Set[Path] = {
    val result = collection.mutable.Set.empty[Path]
    Files.walkFileTree(testDirectory, new SimpleFileVisitor[Path] {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
        visitPath(file)

      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
        visitPath(dir)

      private def visitPath(p: Path): FileVisitResult = {
        result += p
        FileVisitResult.CONTINUE
      }
    })

    result -= testDirectory
    result
  }

  /**
   * Deletes a directory and all its content.
   *
   * @param path the directory to be deleted
   */
  def deleteDirectory(path: Path): Unit = {
    Files.walkFileTree(path, new SimpleFileVisitor[Path] {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files delete file
        FileVisitResult.CONTINUE
      }

      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        if (exc == null) {
          Files delete dir
          FileVisitResult.CONTINUE
        } else throw exc
      }
    })
  }

  /**
   * Deletes a whole directory tree. If the specified path is a directory, it
   * is removed with all its content. If it is only a single file, it is
   * directly removed.
   *
   * @param path the path in question
   */
  def deleteTree(path: Path): Unit = {
    if (Files exists path) {
      if (Files.isDirectory(path))
        deleteDirectory(path)
      else Files delete path
    }
  }

  /**
   * Resolves a file from the (test) resources folder and returns the path to
   * it. Throws an exception if the path specified cannot be resolved.
   *
   * @param path the path to the test file
   * @return the resolved ''Path'' to this test file
   */
  def resourceFile(path: String): Path =
    Paths get getClass.getResource(path).toURI

  /**
   * Checks whether the temporary directory has been created. If not, it is
   * created now. The corresponding path is returned.
   *
   * @return the path to the managed temporary directory
   */
  private def ensureTempDirectory(): Path = {
    optTestDirectory = optTestDirectory orElse Some(createTempDirectory())
    optTestDirectory.get
  }

  /**
   * Creates a temporary directory which is the root folder of all temporary
   * files created by this trait.
   *
   * @return the temporary directory
   */
  private def createTempDirectory(): Path =
    Files createTempDirectory TempFilePrefix
}
