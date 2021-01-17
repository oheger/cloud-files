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

package com.github.cloudfiles.onedrive

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol.DriveItem

import java.time.Instant

/**
 * A module defining the OneDrive-specific implementations for the classes
 * referenced by a file system.
 *
 * OneDrive currently does not support a mechanism to store arbitrary custom
 * properties in the metadata for files and folders. Therefore, the data
 * classes are fully modelled by [[OneDriveJsonProtocol]]. The
 * OneDrive-specific file and folder implementations reference a [[DriveItem]]
 * object storing the underlying data. Their properties obtain their values
 * from this object. The object can also be accessed by client code to evaluate
 * the additional information supported by the OneDrive protocol.
 */
object OneDriveModel {

  /**
   * A trait implementing base functionality needed by OneDrive file and folder
   * implementations.
   *
   * The trait requires access to an underlying ''DriveItem'' object. The
   * properties defined by the ''Element'' trait are mapped to properties of
   * this item.
   */
  trait OneDriveElement extends Model.Element[String] {
    /**
     * Return the ''DriveItem'' that is represented by this element.
     *
     * @return the underlying ''DriveItem''
     */
    def item: DriveItem

    override def id: String = item.id

    override def name: String = item.name

    override def description: String = item.description.orNull

    override def createdAt: Instant = item.createdDateTime

    override def lastModifiedAt: Instant = item.lastModifiedDateTime
  }

  /**
   * The OneDrive-specific implementation of the ''Folder'' trait.
   *
   * @param item the underlying ''DriveItem''
   */
  case class OneDriveFolder(override val item: DriveItem) extends Model.Folder[String] with OneDriveElement {
    /**
     * Returns the ''Folder'' object from the underlying ''DriveItem''. This is
     * a convenience function, which simplifies access to the ''Option''. For
     * folder objects, this information should always be present.
     *
     * @return the ''Folder'' object from the underlying ''DriveItem''
     */
    def folderData: OneDriveJsonProtocol.Folder = {
      assert(item.folder.isDefined, "No folder data available in underlying DriveItem.")
      item.folder.get
    }
  }

  /**
   * The OneDrive-specific implementation of the ''File'' trait.
   *
   * @param item the underlying ''DriveItem''
   */
  case class OneDriveFile(override val item: DriveItem) extends Model.File[String] with OneDriveElement {
    override def size: Long = item.size

    /**
     * Returns the ''File'' object from the underlying ''DriveItem''. This is
     * a convenience function, which simplifies access to the ''Option''. For
     * file objects, this information should be always present.
     *
     * @return the ''File'' from the underlying ''DriveItem''
     */
    def fileData: OneDriveJsonProtocol.File = {
      assert(item.file.isDefined, "No file data available in underlying DriveItem.")
      item.file.get
    }
  }

}
