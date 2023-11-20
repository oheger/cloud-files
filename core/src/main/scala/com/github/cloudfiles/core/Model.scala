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

package com.github.cloudfiles.core

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

/**
 * A module defining basic types to represent files and folders in a cloud
 * storage.
 *
 * The data types introduced by this module define a minimum set of properties
 * that should be common to all concrete implementations of cloud storages.
 * They can be extended by specific implementations to add more properties that
 * are supported by this platform.
 *
 * Note that a concrete implementation of the [[FileSystem]] trait is free to
 * use these data types or not. (The trait itself is agnostic about the
 * concrete types in use.) However, supporting these types simplifies
 * operations between different server types.
 */
object Model {

  /**
   * A trait representing an element (a file or a folder) stored on a server.
   *
   * This is a base trait defining properties common to all elements that can
   * be interacted with.
   *
   * @tparam ID the type of the ID of an element
   */
  trait Element[ID] {
    /**
     * Returns the ID of this element. Via this ID the element can be resolved,
     * e.g. to obtain its metadata or download it (if possible).
     *
     * @return the ID of this element
     */
    def id: ID

    /**
     * Returns the name of this element. The name is typically displayed to the
     * end user to identify this element.
     *
     * @return the name of this element
     */
    def name: String

    /**
     * Returns the description of this element. Not all file systems support
     * such a description, and users typically do not provide one; therefore,
     * this is an ''Option'' and can be ''None''.
     *
     * @return the optional description of this element
     */
    def description: Option[String]

    /**
     * Returns the date when this element has been created.
     *
     * @return the creation date of this element
     */
    def createdAt: Instant

    /**
     * Returns the date of the last modification of this element.
     *
     * @return the date of last modification
     */
    def lastModifiedAt: Instant
  }

  /**
   * A trait representing a folder on a server.
   *
   * This trait allows access to metadata properties of the folder. The content
   * of the folder can be obtained via the [[FileSystem]] API.
   *
   * @tparam ID the type of the ID of a folder
   */
  trait Folder[ID] extends Element[ID]

  /**
   * A trait representing a file stored on a server.
   *
   * The trait allows access to metadata properties of the file. The actual
   * content of the file can be downloaded via the [[FileSystem]] API.
   *
   * @tparam ID the type of the ID of a file
   */
  trait File[ID] extends Element[ID] {
    /**
     * Returns the size of the content of this file.
     *
     * @return the file size
     */
    def size: Long
  }

  /**
   * A data class that stores the content of a specific folder.
   *
   * An instances contains the (sub) folders and files contained in the
   * represented folder. They are organized in maps, so that they can be
   * accessed directly by their IDs.
   *
   * @param folderID the ID of the represented folder
   * @param files    a map with the files contained in this folder
   * @param folders  a map with the sub folders of this folder
   * @tparam ID     the type to represent an ID
   * @tparam FILE   the type to represent a file
   * @tparam FOLDER the type to represent a folder
   */
  case class FolderContent[ID, FILE, FOLDER](folderID: ID,
                                             files: Map[ID, FILE],
                                             folders: Map[ID, FOLDER]) {
    /**
     * Applies mappings to the content stored in this object. The mapping
     * functions that are defined are invoked on the files and folders, and a
     * new instance is created with the results. Note: The mapping functions
     * must not change the IDs of elements.
     *
     * @param mapFiles   optional mapping function on files
     * @param mapFolders optional mapping function on folders
     * @return the result of the mapping
     */
    def mapContent(mapFiles: Option[FILE => FILE] = None,
                   mapFolders: Option[FOLDER => FOLDER] = None): FolderContent[ID, FILE, FOLDER] = {
      val newFiles = mapFiles.fold(files)(f => files.map(e => (e._1, f(e._2))))
      val newFolders = mapFolders.fold(folders)(f => folders.map(e => (e._1, f(e._2))))
      copy(files = newFiles, folders = newFolders)
    }

    /**
     * Applies mappings in parallel to the content stored in this object. This
     * function is analogous to ''mapContent()'', but invocations to mapping
     * functions are wrapped in ''Future'' objects, and therefore run
     * concurrently. The resulting future completes when all mappings are done.
     *
     * @param mapFiles   optional mapping function on files
     * @param mapFolders optional mapping function on folders
     * @param ec         the execution context
     * @return a ''Future'' with the result of the mapping
     */
    def mapContentParallel(mapFiles: Option[FILE => FILE] = None,
                           mapFolders: Option[FOLDER => FOLDER] = None)
                          (implicit ec: ExecutionContext): Future[FolderContent[ID, FILE, FOLDER]] = {
      def mapTried[T](optFunc: Option[T => T]): Option[T => Try[T]] =
        optFunc map { func =>
          (elem: T) => Try(func(elem))
        }

      mapContentParallelTried(mapTried(mapFiles), mapTried(mapFolders)) flatMap { result =>
        result._2.headOption match {
          case Some(failure) => Future.failed(failure.exception)
          case None => Future.successful(result._1)
        }
      }
    }

    /**
     * Applies mappings in parallel to the content stored in this object that
     * can fail. This function is analogous to ''mapContentParallel()'', but
     * the passed in mapping functions return a ''Try''. Result is a ''Future''
     * with a tuple that contains a ''FolderContent'' object with all the
     * files and folders that could be mapped successfully, and a list with
     * failures that occurred during mapping.
     *
     * @param mapFiles   optional mapping function on files
     * @param mapFolders optional mapping function on folders
     * @param ec         the execution context
     * @return a ''Future'' with the results of the mapping
     */
    def mapContentParallelTried(mapFiles: Option[FILE => Try[FILE]] = None,
                                mapFolders: Option[FOLDER => Try[FOLDER]] = None)
                               (implicit ec: ExecutionContext):
    Future[(FolderContent[ID, FILE, FOLDER], List[Failure[ID]])] = {
      val NoFailures = List.empty[Failure[ID]]
      val futFiles = mapFiles.fold(Future.successful((files, NoFailures)))(f => mapInParallel(files)(f))
      val futFolders = mapFolders.fold(Future.successful((folders, NoFailures)))(f => mapInParallel(folders)(f))
      for {
        mappedFiles <- futFiles
        mappedFolders <- futFolders
      } yield (copy(files = mappedFiles._1, folders = mappedFolders._1), mappedFiles._2 ::: mappedFolders._2)
    }

    /**
     * Applies the specified mapping function, which can fail, to all elements
     * of the given map in parallel.
     *
     * @param elements the map with elements
     * @param f        the mapping function
     * @param ec       the execution context
     * @tparam A the value type of the map
     * @return a ''Future'' with the processed map and the encountered failures
     */
    private def mapInParallel[A](elements: Map[ID, A])(f: A => Try[A])(implicit ec: ExecutionContext):
    Future[(Map[ID, A], List[Failure[ID]])] =
      Future.sequence(elements.toList map { e =>
        Future {
          (e._1, f(e._2))
        }
      }) map { mapTried =>
        val (success, failures) = mapTried.partition(_._2.isSuccess)
        val successMap = success.map { e => (e._1, e._2.get) }.toMap
        val failureList = failures.collect {
          case (_, Failure(exception)) => Failure[ID](exception)
        }
        (successMap, failureList)
      }
  }
}
