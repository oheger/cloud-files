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

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.localfs.LocalFsModel.{LocalFile, LocalFolder}

import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.nio.file.{FileSystemException, Files, NotDirectoryException, Path}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

/**
 * A ''FileSystem'' implementation to manipulate files and folders on a local
 * hard disk.
 *
 * This implementation is a bit special, as most of its operations use neither
 * the implicit ''ActorSystem'' nor the actor for sending HTTP requests. (Only
 * the operations that write the content of a file use a stream which depends
 * on the actor system.) Rather, the implementation makes use of Java's
 * ''Path'' and ''Files'' API. These API calls are wrapped in ''Future''
 * objects that run on the ''ExecutionContext'' defined in the associated
 * [[LocalFsConfig]].
 *
 * @param config the configuration for this file system
 */
class LocalFileSystem(val config: LocalFsConfig)
  extends ExtensibleFileSystem[Path, LocalFsModel.LocalFile, LocalFsModel.LocalFolder,
    Model.FolderContent[Path, LocalFsModel.LocalFile, LocalFsModel.LocalFolder]] {
  override def patchFolder(source: Model.Folder[Path], spec: ElementPatchSpec): LocalFsModel.LocalFolder =
    spec.patchName.fold(toLocalFolder(source)) { newName => toLocalFolder(source).copy(name = newName) }

  override def patchFile(source: Model.File[Path], spec: ElementPatchSpec): LocalFsModel.LocalFile = {
    val localFile = toLocalFile(source)
    localFile.copy(name = spec.patchName getOrElse localFile.name,
      size = spec.patchSize getOrElse localFile.size)
  }

  override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[Path] = Operation { _ =>
    Future {
      val resolvedPath = UriEncodingHelper.decodeComponents(path)
      checkPath(config.basePath.resolve(UriEncodingHelper.removeLeadingSeparator(resolvedPath)))
    }
  }

  override def rootID(implicit system: ActorSystem[_]): Operation[Path] = Operation { _ =>
    Future.successful(config.basePath)
  }

  override def resolveFile(id: Path)(implicit system: ActorSystem[_]): Operation[LocalFile] = Operation { _ =>
    Future {
      if (!Files.isRegularFile(checkPath(id), config.linkOptions: _*))
        throw new FileSystemException(id.toString, null, "This path does not point to a file.")

      createLocalFile(id)
    }
  }

  override def resolveFolder(id: Path)(implicit system: ActorSystem[_]): Operation[LocalFolder] = Operation { _ =>
    Future {
      if (!Files.isDirectory(checkPath(id), config.linkOptions: _*))
        throw new NotDirectoryException(id.toString)

      createLocalFolder(id)
    }
  }

  override def folderContent(id: Path)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[Path, LocalFile, LocalFolder]] = Operation { _ =>
    Future {
      val childPaths = readFolder(checkPath(id))
      val elements =
        childPaths.foldLeft((Map.empty[Path, LocalFile], Map.empty[Path, LocalFolder])) { (maps, path) =>
          if (Files.isDirectory(path, config.linkOptions: _*))
            (maps._1, maps._2 + (path -> createLocalFolder(path)))
          else if (Files.isRegularFile(path, config.linkOptions: _*))
            (maps._1 + (path -> createLocalFile(path)), maps._2)
          else maps
        }
      Model.FolderContent(id, elements._1, elements._2)
    }
  }

  override def createFolder(parent: Path, folder: Model.Folder[Path])(implicit system: ActorSystem[_]):
  Operation[Path] = Operation { _ =>
    Future {
      updateProperties(Files.createDirectory(checkPath(parent.resolve(folder.name))), folder)
    }
  }

  override def updateFolder(folder: Model.Folder[Path])(implicit system: ActorSystem[_]): Operation[Unit] =
    Operation { _ =>
      Future {
        updateProperties(checkPath(folder.id), folder)
      }
    }

  override def deleteFolder(folderID: Path)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElement(folderID)

  override def createFile(parent: Path, file: Model.File[Path], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[Path] = Operation { _ =>
    val path = parent.resolve(file.name)
    writeFileContent(path, content) map (_ => updateProperties(path, file))
  }

  override def updateFile(file: Model.File[Path])(implicit system: ActorSystem[_]): Operation[Unit] = Operation { _ =>
    Future {
      updateProperties(checkPath(file.id), file)
    }
  }

  override def updateFileContent(fileID: Path, size: Long, content: Source[ByteString, Any])
                                (implicit system: ActorSystem[_]): Operation[Unit] = Operation { _ =>
    writeFileContent(fileID, content) map (_ => ())
  }

  override def downloadFile(fileID: Path)(implicit system: ActorSystem[_]): Operation[HttpEntity] = Operation { _ =>
    Future {
      HttpEntity(ContentTypes.`application/octet-stream`, FileIO.fromPath(checkPath(fileID)))
    }
  }

  override def deleteFile(fileID: Path)(implicit system: ActorSystem[_]): Operation[Unit] = deleteElement(fileID)

  /**
   * @inheritdoc This implementation returns the execution context from the
   *             file system's configuration.
   */
  override protected implicit def ec(implicit system: ActorSystem[_]): ExecutionContext = config.executionContext

  /**
   * Executes a check whether the given path is in the sub tree of the base
   * path of this file system if this check is enabled. If the check fails, an
   * exception is thrown.
   *
   * @param path the path to check
   * @return the checked path
   */
  private def checkPath(path: Path): Path = {
    if (config.sanitizePaths && !path.normalize().startsWith(config.basePath))
      throw new FileSystemException(path.toString, null, s"not a sub path of '${config.basePath}'.")
    path
  }

  /**
   * Returns the basic attributes of the file or folder referenced by the given
   * path.
   *
   * @param path the path to the element
   * @return the basic attributes of this element
   */
  private def readAttributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, classOf[BasicFileAttributes], config.linkOptions: _*)

  /**
   * Constructs a ''LocalFile'' object for the given path.
   *
   * @param path the path in question
   * @return the ''LocalFile'' representing this path
   */
  private def createLocalFile(path: Path): LocalFile = {
    val attributes = readAttributes(path)
    LocalFile(id = path, name = path.getFileName.toString, createdAt = attributes.creationTime().toInstant,
      lastModifiedAt = attributes.lastModifiedTime().toInstant, size = attributes.size())
  }

  /**
   * Constructs a ''LocalFolder'' object for the given path.
   *
   * @param path the path in question
   * @return the ''LocalFolder'' representing this path
   */
  private def createLocalFolder(path: Path): LocalFolder = {
    val attributes = readAttributes(path)
    LocalFolder(id = path, name = path.getFileName.toString, createdAt = attributes.creationTime().toInstant,
      lastModifiedAt = attributes.lastModifiedTime().toInstant)
  }

  private def deleteElement(path: Path)(implicit system: ActorSystem[_]): Operation[Unit] = Operation { _ =>
    Future {
      Files delete checkPath(path)
    }
  }

  /**
   * Updates the supported properties for elements in the local file system.
   * This function checks whether the passed in element defines any properties
   * to update. If so, they are changed.
   *
   * @param target  the target path
   * @param element the element with the properties to update
   * @return the target path
   */
  private def updateProperties(target: Path, element: Model.Element[Path]): Path = {
    element match {
      case updatable: LocalFsModel.LocalUpdatable =>
        updatable.lastModifiedUpdate foreach { time =>
          Files.setLastModifiedTime(target, FileTime.from(time))
        }
      case _ =>
    }

    target
  }

  /**
   * Writes the content of a file from a given content source.
   *
   * @param target  the target path of the file
   * @param content the source with the file's content
   * @param system  the actor system
   * @return a ''Future'' with the result of the operation
   */
  private def writeFileContent(target: Path, content: Source[ByteString, Any])(implicit system: ActorSystem[_]):
  Future[IOResult] = Future {
    checkPath(target)
  } flatMap { path =>
    val sink = FileIO.toPath(path)
    content.runWith(sink)
  }

  /**
   * Returns a ''LocalFolder'' that is equivalent to the passed in source
   * folder. If the passed in folder is already a ''LocalFolder'', it is taken
   * as is; otherwise, a new ''LocalFolder'' object is created, and the
   * properties are copied.
   *
   * @param source the source folder
   * @return the resulting local folder
   */
  private def toLocalFolder(source: Model.Folder[Path]): LocalFolder = source match {
    case localFolder: LocalFolder => localFolder
    case folder => LocalFolder(folder.id, folder.name, folder.createdAt, folder.lastModifiedAt)
  }

  /**
   * Returns a ''LocalFile'' that is equivalent to the passed in source
   * file. If the passed in file is already a ''LocalFile'', it is taken
   * as is; otherwise, a new ''LocalFile'' object is created, and the
   * properties are copied.
   *
   * @param source the source file
   * @return the resulting local file
   */
  private def toLocalFile(source: Model.File[Path]): LocalFile = source match {
    case localFile: LocalFile => localFile
    case file => LocalFile(file.id, file.name, file.createdAt, file.lastModifiedAt, file.size)
  }

  /**
   * Reads the content of a the given folder using a Java ''DirectoryStream''
   * and converts it to a list. Note: Because of problems with Scala's
   * collection converter and cross compilation, a manual conversion is done.
   *
   * @param target the path of the folder to read
   * @return a list with the paths to elements contained in this folder
   */
  private def readFolder(target: Path): List[Path] = {
    @tailrec def nextPath(stream: java.util.Iterator[Path], paths: List[Path]): List[Path] =
      if (stream.hasNext) {
        val path = stream.next()
        nextPath(stream, path :: paths)
      } else paths

    val stream = Files.newDirectoryStream(target)
    try {
      nextPath(stream.iterator(), Nil)
    } finally {
      stream.close()
    }
  }
}
