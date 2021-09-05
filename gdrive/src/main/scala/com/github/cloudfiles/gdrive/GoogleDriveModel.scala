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
   * Constant for the mime type used to mark a file in GoogleDrive as a folder.
   */
  final val MimeTypeGoogleFolder = "application/vnd.google-apps.folder"

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

    override def description: Option[String] = googleFile.description

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

  /**
   * Constructs a ''GoogleDriveFolder'' object for creating or updating a
   * folder in the GoogleDrive. For such an operation, typically only parts of
   * the properties supported by a Google folder need to be specified.
   * Therefore, this function sets default values for all properties, and the
   * caller only has to specify the relevant ones.
   *
   * @param name           the name of the folder
   * @param id             the folder ID (needed for update operations)
   * @param description    the description
   * @param createdAt      the time the folder was created
   * @param lastModifiedAt the time of the last modification
   * @param properties     a map with properties for the folder
   * @param appProperties  a map with application-specific properties
   * @return the new ''GoogleDriveFolder'' object
   */
  def newFolder(name: String = null, id: String = null, description: String = null, createdAt: Instant = null,
                lastModifiedAt: Instant = null, properties: Map[String, String] = Map.empty,
                appProperties: Map[String, String] = Map.empty): GoogleDriveFolder = {
    val googleFile = createGoogleFile(name, id, None, description, createdAt, lastModifiedAt, properties,
      appProperties, MimeTypeGoogleFolder)
    GoogleDriveFolder(googleFile)
  }

  /**
   * Constructs a ''GoogleDriveFile'' object for creating or updating a
   * file in the GoogleDrive. For such an operation, typically only parts of
   * the properties supported by a Google file need to be specified.
   * Therefore, this function sets default values for all properties, and the
   * caller only has to specify the relevant ones.
   *
   * @param name           the name of the file
   * @param id             the file ID (needed for update operations)
   * @param size           the size of the file; note that this property must
   *                       be set when uploading the file's content
   * @param description    the description
   * @param createdAt      the time the file was created
   * @param lastModifiedAt the time of the last modification
   * @param properties     a map with properties for the file
   * @param appProperties  a map with application-specific properties
   * @param mimeType       the mime type of the file
   * @return the new ''GoogleDriveFile'' object
   */
  def newFile(name: String = null, id: String = null, size: Long = -1, description: String = null,
              createdAt: Instant = null, lastModifiedAt: Instant = null, properties: Map[String, String] = Map.empty,
              appProperties: Map[String, String] = Map.empty, mimeType: String = null): GoogleDriveFile = {
    val fileSize = Some(size).filter(_ >= 0).map(_.toString)
    val googleFile = createGoogleFile(name, id, fileSize, description, createdAt, lastModifiedAt, properties,
      appProperties, mimeType)
    GoogleDriveFile(googleFile)
  }

  /**
   * Creates a [[File]] object from the passed in parameters.
   *
   * @param name           the name
   * @param id             the ID
   * @param size           the optional file size
   * @param description    the description
   * @param createdAt      the creation time
   * @param lastModifiedAt the last modified time
   * @param properties     properties of the file
   * @param appProperties  application-specific properties of the file
   * @param mimeType       the mime type
   * @return the ''File'' with these properties
   */
  private def createGoogleFile(name: String, id: String, size: Option[String], description: String,
                               createdAt: Instant, lastModifiedAt: Instant, properties: Map[String, String],
                               appProperties: Map[String, String], mimeType: String): File =
    File(id, name, mimeType, List.empty, createdAt, lastModifiedAt, Option(description),
      size, optionalMap(properties), optionalMap(appProperties))

  /**
   * Converts the given map to an ''Option'', treating an empty map as
   * undefined.
   *
   * @param map the map to convert
   * @return the ''Option'' for the map
   */
  private def optionalMap(map: Map[String, String]): Option[Map[String, String]] =
    Option(map).filterNot(_.isEmpty)
}
