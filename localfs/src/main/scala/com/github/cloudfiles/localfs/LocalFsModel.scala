/*
 * Copyright 2020-2022 The Developers Team.
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

import com.github.cloudfiles.core.Model

import java.nio.file.Path
import java.time.Instant

object LocalFsModel {
  /**
   * Constant for a time that is used if no information is available. Rather
   * than having '''null''' values in file or folder objects, this value is
   * used.
   */
  final val TimeUndefined = Instant.EPOCH

  /**
   * A trait that defines the properties that can be updated for elements in
   * the local file system.
   *
   * The trait is implemented by the representations for local files and
   * folders. This makes it possible to treat these classes analogously when it
   * comes to updates.
   */
  trait LocalUpdatable {
    /**
     * Returns an ''Option'' with the new last modified date. If this option is
     * defined, the date gets updated.
     *
     * @return an ''Option'' with the new last modified date
     */
    def lastModifiedUpdate: Option[Instant]
  }

  /**
   * A data class representing a folder in the local file system.
   *
   * This class is used to read the attributes of a local folder. In addition,
   * it has properties that are evaluated when a folder is created or updated.
   *
   * @param id                 the path to the folder
   * @param name               the name of the folder
   * @param createdAt          the folder's creation time
   * @param lastModifiedAt     the folder's last modified time
   * @param lastModifiedUpdate optional property to update the last modified
   *                           time
   */
  case class LocalFolder(override val id: Path,
                         override val name: String,
                         override val createdAt: Instant,
                         override val lastModifiedAt: Instant,
                         override val lastModifiedUpdate: Option[Instant] = None)
    extends Model.Folder[Path] with LocalUpdatable {
    override def description: Option[String] = None
  }

  /**
   * A data class representing a file in the local file system.
   *
   * This class contains the attributes of a local file supported by the
   * implementation. When a file is resolved, the values of these attributes
   * are determined and stored in an instance - so they do not provide a life
   * view to the underlying file system object.
   *
   * When creating or updating a file it is possible to set some selected
   * attributes. This is done by setting the properties starting with the
   * prefix ''update''.
   *
   * @param id                 the path to the file
   * @param name               the name of the file
   * @param createdAt          the file's creation time
   * @param lastModifiedAt     the file's last modified time
   * @param size               the file size (in bytes)
   * @param lastModifiedUpdate optional property to update the last modified
   *                           time
   */
  case class LocalFile(override val id: Path,
                       override val name: String,
                       override val createdAt: Instant,
                       override val lastModifiedAt: Instant,
                       override val size: Long,
                       override val lastModifiedUpdate: Option[Instant] = None)
    extends Model.File[Path] with LocalUpdatable {
    override def description: Option[String] = None
  }

  /**
   * Constructs a ''LocalFolder'' object that can be used to create or update a
   * folder. This is a convenience function that takes only the attributes into
   * account that are relevant for a manipulation of the folder; read-only
   * properties are ignored.
   *
   * @param path           the optional path of the folder
   * @param name           the name of the folder; if undefined, it is derived
   *                       from the path if present
   * @param lastModifiedAt an optional last modified time to set
   * @return the ''LocalFolder'' object to create a new folder
   */
  def newFolder(path: Path = null, name: String = null, lastModifiedAt: Option[Instant] = None): LocalFolder =
    LocalFolder(id = path, name = calcName(path, name), createdAt = TimeUndefined, lastModifiedAt = TimeUndefined,
      lastModifiedUpdate = lastModifiedAt)

  /**
   * Constructs a ''LocalFile'' object that can be used to create or update a
   * file. This is a convenience function that takes only the attributes into
   * account that are relevant for a modification of the file; read-only
   * properties are ignored.
   *
   * @param path           the optional path of the file
   * @param name           the name of the file; if undefined, it is derived
   *                       from the path if present
   * @param lastModifiedAt an optional last modified time to set
   * @return the ''LocalFile'' object to create a new file
   */
  def newFile(path: Path = null, name: String = null, lastModifiedAt: Option[Instant] = None): LocalFile =
    LocalFile(id = path, name = calcName(path, name), createdAt = TimeUndefined, lastModifiedAt = TimeUndefined,
      size = 0, lastModifiedUpdate = lastModifiedAt)

  /**
   * Extracts a file name from a ''Path'', handling the case that the name
   * might be undefined; in this case, '''null''' is returned.
   *
   * @param path the path
   * @return the file name
   */
  private def nameFromPath(path: Path): String = {
    val namePath = path.getFileName
    if (namePath == null) null else namePath.toString
  }

  /**
   * Calculates the name of an element from the given parameters. If a name is
   * provided explicitly, it is used. Otherwise, if a path is available, the
   * name is derived from the path's file name.
   *
   * @param path the path (can be '''null''')
   * @param name the name (can be '''null''')
   * @return the resulting element name
   */
  private def calcName(path: Path, name: String): String = {
    if (name != null) name
    else if (path != null) nameFromPath(path)
    else null
  }
}
