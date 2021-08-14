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

package com.github.cloudfiles.gdrive

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.gdrive.GoogleDriveJsonProtocol.File

import java.time.Instant

/**
 * A module defining the GoogleDrive-specific implementations for the classes
 * referenced by a file system.
 *
 * In GoogleDrive, files and folders are both represented by the ''File''
 * resource. A ''File'' object contains basic properties, some Google-specific
 * properties (such as permissions or information about the shared state), and
 * maps with arbitrary global or application-specific properties.
 * [[GoogleDriveJsonProtocol]] defines data classes that correspond to these
 * structures.
 *
 * The GoogleDrive-specific file and folder implementations reference such a
 * [[GoogleDriveJsonProtocol.File]] object storing the underlying data. Their
 * properties obtain their values from this object. The object can also be
 * accessed by client code to access the additional information supported by
 * the GoogleDrive protocol.
 */
object GoogleDriveModel {
  /**
   * A trait implementing basic functionality required by the GoogleDrive
   * implementations for ''FileSystem'' files and folders.
   *
   * This trait expect that the underlying data is available as a [[File]]
   * object. It implements the basic properties by delegating to this object.
   */
  trait GoogleDriveElement extends Model.Element[String] {
    /**
     * Returns the underlying ''File'' that is represented by this
     * element.
     *
     * @return the underlying Google ''File'' object
     */
    def googleFile: File

    override def id: String = googleFile.id

    override def name: String = googleFile.name

    override def description: String = googleFile.description.orNull

    override def createdAt: Instant = googleFile.createdTime

    override def lastModifiedAt: Instant = googleFile.modifiedTime
  }

  /**
   * The GoogleDrive-specific implementation of the ''Folder'' trait.
   *
   * @param googleFile the underlying Google ''File'' object
   */
  case class GoogleDriveFolder(override val googleFile: File) extends Model.Folder[String] with GoogleDriveElement

  /**
   * The GoogleDrive-specific implementation of the ''File'' trait
   *
   * @param googleFile the underlying Google ''File'' object
   */
  case class GoogleDriveFile(override val googleFile: File) extends Model.File[String] with GoogleDriveElement {
    override def size: Long = googleFile.fileSize
  }
}
