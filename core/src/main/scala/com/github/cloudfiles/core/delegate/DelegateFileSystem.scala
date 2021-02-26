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

package com.github.cloudfiles.core.delegate

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model

/**
 * A trait implementing an ''ExtensibleFileSystem'' that delegates all
 * operations to another file system, acting as delegate.
 *
 * This trait can be used as a base for implementing extension file systems
 * that operate on top of another file system and add specific functionality.
 * Such extension file system implementations can extend this trait and
 * override the operations they need to adapt, while all other operations are
 * performed by the underlying file system directly.
 *
 * @tparam ID     the type of element IDs
 * @tparam FILE   the type to represent a file
 * @tparam FOLDER the type to represent a folder
 */
trait DelegateFileSystem[ID, FILE, FOLDER]
  extends ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]] {
  /**
   * Returns the ''FileSystem'' to which operations are delegated by default.
   *
   * @return the ''FileSystem'' to delegate to
   */
  def delegate: ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]]

  override def patchFolder(source: Model.Folder[ID], spec: ElementPatchSpec): FOLDER =
    delegate.patchFolder(source, spec)

  override def patchFile(source: Model.File[ID], spec: ElementPatchSpec): FILE = delegate.patchFile(source, spec)

  override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[ID] = delegate.resolvePath(path)

  override def resolvePathComponents(components: Seq[String])(implicit system: ActorSystem[_]): Operation[ID] =
    delegate.resolvePathComponents(components)

  override def rootID(implicit system: ActorSystem[_]): Operation[ID] = delegate.rootID

  override def resolveFile(id: ID)(implicit system: ActorSystem[_]): Operation[FILE] = delegate.resolveFile(id)

  override def resolveFolder(id: ID)(implicit system: ActorSystem[_]): Operation[FOLDER] = delegate.resolveFolder(id)

  override def folderContent(id: ID)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[ID, FILE, FOLDER]] = delegate.folderContent(id)

  override def createFolder(parent: ID, folder: Model.Folder[ID])(implicit system: ActorSystem[_]): Operation[ID] =
    delegate.createFolder(parent, folder)

  override def updateFolder(folder: Model.Folder[ID])(implicit system: ActorSystem[_]): Operation[Unit] =
    delegate.updateFolder(folder)

  override def deleteFolder(folderID: ID)(implicit system: ActorSystem[_]): Operation[Unit] =
    delegate.deleteFolder(folderID)

  override def createFile(parent: ID, file: Model.File[ID], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[ID] =
    delegate.createFile(parent, file, content)

  override def updateFile(file: Model.File[ID])(implicit system: ActorSystem[_]): Operation[Unit] =
    delegate.updateFile(file)

  override def updateFileContent(fileID: ID, size: Long, content: Source[ByteString, Any])
                                (implicit system: ActorSystem[_]): Operation[Unit] =
    delegate.updateFileContent(fileID, size, content)

  override def downloadFile(fileID: ID)(implicit system: ActorSystem[_]): Operation[HttpEntity] =
    delegate.downloadFile(fileID)

  override def deleteFile(fileID: ID)(implicit system: ActorSystem[_]): Operation[Unit] =
    delegate.deleteFile(fileID)
}
