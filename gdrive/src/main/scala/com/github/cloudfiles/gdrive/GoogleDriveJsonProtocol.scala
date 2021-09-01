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

import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, RootJsonFormat, deserializationError}

import java.time.Instant

/**
 * A module defining data classes that correspond to the GoogleDrive REST API
 * as described at
 * https://developers.google.com/drive/api/v3/reference/files.
 *
 * The GoogleDrive REST API uses JSON as data exchange format. The classes
 * defined here are used to read and write JSON to and from Scala objects.
 *
 * In addition, the module defines converters for these data classes to enable
 * a proper JSON serialization. They are available as implicits, which must be
 * available in the current scope to make the conversion to and from JSON
 * possible.
 */
object GoogleDriveJsonProtocol extends DefaultJsonProtocol {
  /**
   * A data class corresponding to the GoogleDrive ''File'' resource.
   *
   * This resource is used to represent files and folders stored on a
   * GoogleDrive. The ''FileSystem'' implementation for GoogleDrive mainly
   * interacts with this resource.
   *
   * Refer to https://developers.google.com/drive/api/v3/reference/files.
   *
   * @param id            the ID of the file
   * @param name          the name of the file (or folder)
   * @param mimeType      the mime type
   * @param parents       a list with the parents of this file
   * @param createdTime   the creation time
   * @param modifiedTime  the time of the last modification
   * @param description   the optional description for this file
   * @param size          the size of the file
   * @param properties    optional properties assigned to this file
   * @param appProperties optional application-specific properties
   */
  case class File(id: String,
                  name: String,
                  mimeType: String,
                  parents: List[String],
                  createdTime: Instant,
                  modifiedTime: Instant,
                  description: Option[String],
                  size: Option[String],
                  properties: Option[Map[String, String]],
                  appProperties: Option[Map[String, String]]) {
    /**
     * Returns the size of this file as a ''Long'' value. The string-based size
     * from the original data is converted. In case of a folder, no size is
     * present; then result is 0.
     *
     * @return the numeric size of this file
     */
    def fileSize: Long = size map (_.toLong) getOrElse 0

    /**
     * Returns global properties for this file. This function returns an empty
     * map if no properties are defined.
     *
     * @return a map with the globally visible properties of this file
     */
    def fileProperties: Map[String, String] = properties getOrElse Map.empty

    /**
     * Returns application-specific properties for this file. This function
     * returns an empty map if no properties are defined.
     *
     * @return a map with application-specific properties of this file
     */
    def fileAppProperties: Map[String, String] = appProperties getOrElse Map.empty
  }

  /**
   * A data class representing a ''File'' resource when it is created or
   * updated.
   *
   * This class has a subset of the properties of [[File]], and all are
   * optional. These are the writable properties that can be set in create or
   * update operations. The class is used to generate the JSON payload for
   * these operations.
   *
   * @param name          the optional new name of the file
   * @param mimeType      the optional new mime type
   * @param parents       the optional parents
   * @param createdTime   the optional new creation time
   * @param modifiedTime  the optional new modified time
   * @param description   the optional new description
   * @param properties    the optional new properties
   * @param appProperties the optional new application-specific properties
   */
  case class WritableFile(name: Option[String],
                          mimeType: Option[String],
                          parents: Option[List[String]],
                          createdTime: Option[Instant],
                          modifiedTime: Option[Instant],
                          description: Option[String],
                          properties: Option[Map[String, String]],
                          appProperties: Option[Map[String, String]])

  /**
   * A data class describing the content of a folder with all its files.
   *
   * In Google Drive, there is the single type [[File]] representing both files
   * and folders; so a single list with elements of this type is sufficient to
   * hold all the elements the folder contains.
   *
   * @param files         the elements contained in the folder
   * @param nextPageToken an optional token to query the next result page
   */
  case class FolderResponse(files: List[File],
                            nextPageToken: Option[String])

  /**
   * A data class representing a reference to a file.
   *
   * This class is used for resolve operation, where only file IDs are
   * relevant. Other information is ignored.
   *
   * @param id the ID of the referenced file
   */
  case class FileReference(id: String)

  /**
   * A data class describing the response of a query to resolve files.
   *
   * The query requests only the ID field of files; therefore, only
   * [[FileReference]] objects are constructed. Paging needs to be supported as
   * well.
   *
   * @param files         the elements returned by the query
   * @param nextPageToken an optional token to query the next result page
   */
  case class ResolveResponse(files: List[FileReference],
                             nextPageToken: Option[String])

  /**
   * A format implementation to deal with date-time values. GoogleDrive uses
   * the default ISO format that can be parsed by the ''Instant'' class.
   * Therefore, this implementation is straight-forward.
   */
  implicit object InstantFormat extends JsonFormat[Instant] {
    override def read(json: JsValue): Instant = json match {
      case JsString(value) => Instant.parse(value)
      case j => deserializationError(s"Expected a string for an instant, but was: $j.")
    }

    override def write(obj: Instant): JsValue = JsString(obj.toString)
  }

  implicit val fileFormat: RootJsonFormat[File] = jsonFormat10(File)
  implicit val writableFileFormat: RootJsonFormat[WritableFile] = jsonFormat8(WritableFile)
  implicit val folderResponseFormat: RootJsonFormat[FolderResponse] = jsonFormat2(FolderResponse)
  implicit val fileReferenceFormat: RootJsonFormat[FileReference] = jsonFormat1(FileReference)
  implicit val resolveResponseFormat: RootJsonFormat[ResolveResponse] = jsonFormat2(ResolveResponse)
}
