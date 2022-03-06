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

package com.github.cloudfiles.onedrive

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol._

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
   * Constant for a data structure used to mark a drive item as a folder.
   */
  private val FolderMarker = Some(Folder(0))

  /**
   * Constant for a data structure used to mark a drive item as a file.
   */
  private val FileMarker = Some(File("", null))

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

    override def description: Option[String] = item.description

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
    def folderData: Folder = {
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
    def fileData: File = {
      assert(item.file.isDefined, "No file data available in underlying DriveItem.")
      item.file.get
    }
  }

  /**
   * Constructs a ''OneDriveFolder'' object for creating or updating a folder
   * on the server. As only a small number of a folder's properties are
   * writable, creating such an object manually and passing in all the dummy
   * values is cumbersome. Therefore, this utility function can be used, which
   * deals only with relevant properties and sets defaults for all others.
   *
   * @param name        the name of the folder
   * @param id          the ID of the folder or '''null''' for undefined
   * @param description the description or '''null''' for undefined
   * @param info        optional object with local file system information
   * @return the newly created ''OneDriveFolder'' object
   */
  def newFolder(name: String, id: String = null, description: Option[String] = None,
                info: Option[WritableFileSystemInfo] = None): OneDriveFolder =
    OneDriveFolder(itemForFolder(id, name, description, info))

  /**
   * Constructs a ''OneDriveFile'' object for creating or updating a file on
   * the server. As only a small number of a file's properties are writable,
   * creating such an object manually and passing in all the dummy values is
   * cumbersome. Therefore, this utility function can be used, which deals only
   * with relevant properties and sets defaults for all others. Note that the
   * size of the file is required for a correct upload.
   *
   * @param size        the size of the file (in bytes)
   * @param id          the ID of the file or '''null''' for undefined
   * @param name        the name of the file or '''null''' for undefined
   * @param description the description or '''null''' for undefined
   * @param info        optional object with local file system information
   * @return the newly created ''OneDriveFile'' object
   */
  def newFile(size: Long, name: String, id: String = null, description: Option[String] = None,
              info: Option[WritableFileSystemInfo] = None): OneDriveFile =
    OneDriveFile(itemForFile(id, name, description, size, info))

  /**
   * Constructs a ''FileSystemInfo'' object based on the passed in ''Option''
   * of a ''WritableFileSystemInfo''.
   *
   * @param info the optional ''WritableFileSystemInfo''
   * @return the resulting ''FileSystemInfo''
   */
  private def createFileSystemInfoFor(info: Option[WritableFileSystemInfo] = None): FileSystemInfo =
    info.flatMap(checkFileSystemInfoDefined)
      .map { fsi =>
        FileSystemInfo(createdDateTime = fsi.createdDateTime.orNull,
          lastModifiedDateTime = fsi.lastModifiedDateTime.orNull,
          lastAccessedDateTime = fsi.lastAccessedDateTime)
      }.orNull

  /**
   * Constructs a ''DriveItem'' object to represent the folder with the given
   * properties.
   *
   * @param id          the folder ID
   * @param name        the optional folder name
   * @param description the optional folder description
   * @param info        the optional file system info
   * @return a ''DriveItem'' representing this folder
   */
  private def itemForFolder(id: String, name: String, description: Option[String],
                            info: Option[WritableFileSystemInfo]): DriveItem =
    itemForElement(id, name, description, 0, info, optFile = None, optFolder = FolderMarker)

  /**
   * Constructs a ''DriveItem'' object to represent the file with the given
   * properties.
   *
   * @param id          the file ID
   * @param name        the optional file name
   * @param description the optional file description
   * @param size        the file size
   * @param info        the optional file system info
   * @return a ''DriveItem'' representing this folder
   */
  private def itemForFile(id: String, name: String, description: Option[String], size: Long,
                          info: Option[WritableFileSystemInfo]): DriveItem =
    itemForElement(id, name, description, size, info, optFile = FileMarker, optFolder = None)

  /**
   * Constructs a ''DriveItem'' object to represent the element with the given
   * properties.
   *
   * @param id          the element ID
   * @param name        the optional element name
   * @param description the optional element description
   * @param size        the size of the item
   * @param info        the optional file system info
   * @param optFile     the optional file structure
   * @param optFolder   the optional folder structure
   * @return a ''DriveItem'' representing this element
   */
  private def itemForElement(id: String, name: String, description: Option[String], size: Long,
                             info: Option[WritableFileSystemInfo], optFile: Option[File],
                             optFolder: Option[Folder]): DriveItem =
    DriveItem(id = id, name = name, description = description,
      createdBy = null, createdDateTime = null, lastModifiedBy = null, lastModifiedDateTime = null,
      size = size, webUrl = null, file = optFile, folder = optFolder,
      fileSystemInfo = createFileSystemInfoFor(info), parentReference = None, shared = None,
      specialFolder = None)

  /**
   * Checks whether the given file system info has at least one defined
   * property. If not, the whole object does not need to appear in JSON, and
   * therefore, the function return ''None''.
   *
   * @param info the object to check
   * @return an ''Option'' with this object if defined; ''None'' otherwise
   */
  private def checkFileSystemInfoDefined(info: WritableFileSystemInfo): Option[WritableFileSystemInfo] =
    if (List(info.createdDateTime, info.lastModifiedDateTime, info.lastAccessedDateTime).exists(_.isDefined))
      Some(info)
    else None
}
