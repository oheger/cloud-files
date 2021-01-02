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

package com.github.cloudfiles

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.HttpEntity
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.FileSystem.Operation
import com.github.cloudfiles.http.HttpRequestSender

import scala.concurrent.{ExecutionContext, Future}

object FileSystem {

  /**
   * A type to represent an operation on a [[FileSystem]]. This type is
   * returned by the functions of the ''FileSystem'' trait. It is basically a
   * description of an operation to be executed. The operation is executed by
   * invoking the ''run()'' function of this object. The ''run()'' function
   * expects an actor to execute an HTTP request and returns a ''Future'' with
   * the result.
   *
   * @param run the function to execute this operation
   * @tparam A the result type of this operation
   */
  case class Operation[A](run: ActorRef[HttpRequestSender.HttpCommand] => Future[A]) {
    def flatMap[B](f: A => Operation[B])(implicit ec: ExecutionContext): Operation[B] = Operation(actor =>
      run(actor) flatMap { a => f(a).run(actor) }
    )

    def map[B](f: A => B)(implicit ec: ExecutionContext): Operation[B] = Operation(actor =>
      run(actor) map f
    )
  }

}

/**
 * A trait defining typical operations on files and folders that are stored on
 * a server.
 *
 * This trait defines the API of a file system. This project provides different
 * implementations supporting different server-based protocols, such as
 * OneDrive or WebDAV.
 *
 * The protocol defined by this trait is agnostic to the concrete types
 * representing files and folders in the file systems. The single operations
 * are asynchronous; they expect an actor reference of type
 * [[HttpRequestSender]] to be passed in and yield a ''Future'' with the
 * result. This is indicated by the [[Operation]] result type. [[Operation]] is
 * a monad, so multiple operations can be composed.
 *
 * @tparam ID             the type representing the ID of a file or folder
 * @tparam FILE           the type representing a file
 * @tparam FOLDER         the type representing a folder
 * @tparam FOLDER_CONTENT the type representing the content of a folder
 */
trait FileSystem[ID, FILE, FOLDER, FOLDER_CONTENT] {
  /**
   * Resolves the ID of an element (file or folder) that is specified by its
   * path. In a hierarchical file system, there is typically a direct
   * relation between a path and a URI that uniquely identifies an element. But
   * no all file systems are hierarchical; e.g. in Google Drive a single file
   * can be contained in multiple folders, so a resolve operation can be
   * actually complex.
   *
   * @param path   the path to be resolved
   * @param system the actor system
   * @return the ''Operation'' to resolve the path
   */
  def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[ID]

  /**
   * Returns the ID of the root folder of this file system. This can be used as
   * starting point to iterate over the content stored in the file system.
   *
   * @param system the actor system
   * @return the ''Operation'' returning the ID of the file system root
   */
  def rootID(implicit system: ActorSystem[_]): Operation[ID]

  /**
   * Returns the file identified by the given ID.
   *
   * @param id     the ID of the file in question
   * @param system the actor system
   * @return the ''Operation'' returning the file with this ID
   */
  def resolveFile(id: ID)(implicit system: ActorSystem[_]): Operation[FILE]

  /**
   * Returns the folder identified by the given ID.
   *
   * @param id     the ID of the folder in question
   * @param system the actor system
   * @return the ''Operation'' returning the folder with this ID
   */
  def resolveFolder(id: ID)(implicit system: ActorSystem[_]): Operation[FOLDER]

  /**
   * Returns an object describing the content of the folder with the given ID.
   * This can be used to find the files and the folders that are the children
   * of the folder with the ID specified.
   *
   * @param id     the ID of the folder in question
   * @param system the actor system
   * @return the ''Operation'' returning the content of this folder
   */
  def folderContent(id: ID)(implicit system: ActorSystem[_]): Operation[FOLDER_CONTENT]

  /**
   * Creates a folder as a child of the given parent folder. The attributes of
   * the new folder are defined by the folder object provided. If the operation
   * is successful, the ID of the new folder is returned.
   *
   * @param parent the ID of the parent folder
   * @param folder an object with the attributes of the new folder
   * @param system the actor system
   * @return the ''Operation'' to create a new folder
   */
  def createFolder(parent: ID, folder: Model.Folder[ID])(implicit system: ActorSystem[_]): Operation[ID]

  /**
   * Updates the metadata of a folder. The passed in folder object must contain
   * the ID of the folder affected and the attributes to update.
   *
   * @param folder the object representing the folder
   * @param system the actor system
   * @return the ''Operation'' to update folder metadata
   */
  def updateFolder(folder: Model.Folder[ID])(implicit system: ActorSystem[_]): Operation[Unit]

  /**
   * Deletes the folder with the given ID. Note that depending on the concrete
   * implementation, it may be required that a folder is empty before it can be
   * deleted. The root folder can typically not be deleted.
   *
   * @param folderID the ID of the folder to delete
   * @param system   the actor system
   * @return the ''Operation'' to delete a folder
   */
  def deleteFolder(folderID: ID)(implicit system: ActorSystem[_]): Operation[Unit]

  /**
   * Creates a file as a child of the given parent folder by uploading the
   * file's content and setting some metadata attributes. The attributes to set
   * are provided in form of a file object; typically some properties are
   * mandatory, such as the file name and the file size. If the operation is
   * successful, the ID of the new file is returned.
   *
   * @param parent  the ID of the parent folder
   * @param file    an object with the attributes of the new file
   * @param content a ''Source'' with the content of the file
   * @param system  the actor system
   * @return the ''Operation'' to create a new file
   */
  def createFile(parent: ID, file: Model.File[ID], content: Source[ByteString, Any])
                (implicit system: ActorSystem[_]): Operation[ID]

  /**
   * Updates the metadata of a file. The passed in file object must contain the
   * ID of the file affected and the attributes to set.
   *
   * @param file   the object representing the file
   * @param system the actor system
   * @return the ''Operation'' to update file metadata
   */
  def updateFile(file: Model.File[ID])(implicit system: ActorSystem[_]): Operation[Unit]

  /**
   * Updates the content of a file by uploading new data to the server.
   *
   * @param fileID  the ID of the file affected
   * @param size    the size of the new content
   * @param content a ''Source'' with the content of the file
   * @param system the actor system
   * @return the ''Operation'' to upload new file content
   */
  def updateFileContent(fileID: ID, size: Int, content: Source[ByteString, Any])
                       (implicit system: ActorSystem[_]): Operation[Unit]

  /**
   * Returns an entity for downloading the content of a file. The download can
   * be performed by creating a stream with the data bytes of the resulting
   * entity. Note that the entity must be consumed in all cases, either by
   * running the stream with the data bytes or by discarding the bytes.
   *
   * @param fileID the ID of the file affected
   * @param system the actor system
   * @return the ''Operation'' to obtain an entity for downloading a file
   */
  def downloadFile(fileID: ID)(implicit system: ActorSystem[_]): Operation[HttpEntity]

  /**
   * Deletes the file with the given ID.
   *
   * @param fileID the ID of the file to delete
   * @param system the actor system
   * @return the ''Operation'' to delete the file
   */
  def deleteFile(fileID: ID)(implicit system: ActorSystem[_]): Operation[Unit]

  /**
   * Returns the folder object that is referenced by the path specified. This
   * is a combination of resolving the path and obtaining the folder with the
   * resulting ID.
   *
   * @param path   the path to the desired folder
   * @param system the actor system
   * @return the ''Operation'' to resolve the folder with this path
   */
  def resolveFolderByPath(path: String)(implicit system: ActorSystem[_]): Operation[FOLDER] =
    for {
      id <- resolvePath(path)
      folder <- resolveFolder(id)
    } yield folder

  /**
   * Returns the file object that is referenced by the path specified. This is
   * a combination of resolving the path and obtaining the file with the
   * resulting ID.
   *
   * @param path   the path of the desired file
   * @param system the actor system
   * @return the ''Operation'' to resolve the file with this path
   */
  def resolveFileByPath(path: String)(implicit system: ActorSystem[_]): Operation[FILE] =
    for {
      id <- resolvePath(path)
      file <- resolveFile(id)
    } yield file

  /**
   * Returns an object with the content of the root folder. This is a
   * combination of requesting the root folder ID and requesting the content of
   * this folder.
   *
   * @param system the actor system
   * @return the ''Operation'' to obtain the content of the root folder
   */
  def rootFolderContent(implicit system: ActorSystem[_]): Operation[FOLDER_CONTENT] =
    for {
      rootId <- rootID
      content <- folderContent(rootId)
    } yield content

  /**
   * Helper function to obtain an implicit execution context from an implicit
   * actor system.
   *
   * @param system the actor system
   * @return the execution context in implicit scope
   */
  private implicit def ec(implicit system: ActorSystem[_]): ExecutionContext = system.executionContext
}
