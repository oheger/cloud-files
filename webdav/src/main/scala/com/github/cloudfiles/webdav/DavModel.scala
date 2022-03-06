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

package com.github.cloudfiles.webdav

import akka.http.scaladsl.model.Uri
import com.github.cloudfiles.core.Model

import java.time.Instant

/**
 * A module defining the concrete data types used by the WebDav protocol to
 * represent files and folders.
 */
object DavModel {

  /**
   * A data class describing the key of an attribute of a file or a folder.
   *
   * WebDav is based on XML; therefore, attributes are actually XML elements.
   * They are identified by a namespace and an element name.
   *
   * @param namespace the namespace of this key
   * @param key       the actual key, corresponding to the element name
   */
  case class AttributeKey(namespace: String, key: String)

  /**
   * A data class representing additional attributes assigned to a folder or a
   * file.
   *
   * An instance of this class stores additional metadata of an element hold by
   * the server, which is not already covered by the default properties. When
   * parsing a response from the server, this information is extracted and made
   * available.
   *
   * When updating metadata, it is also possible to set arbitrary properties.
   * To also support deleting existing properties, this class contains a
   * sequence with the keys of properties that should be removed. It is
   * evaluated only by update operations.
   *
   * @param values       the attribute values
   * @param keysToDelete a list with keys of attributes to delete
   */
  case class Attributes(values: Map[AttributeKey, String],
                        keysToDelete: Seq[AttributeKey] = Nil) {
    /**
     * Returns a copy of this instance with the specified attribute added.
     *
     * @param namespace the namespace of the attribute key
     * @param key       the attribute key
     * @param value     the value of the attribute
     * @return the new instance with the attribute added
     */
    def withAttribute(namespace: String, key: String, value: String): Attributes =
      withAttribute(AttributeKey(namespace, key), value)

    /**
     * Returns a copy of this instance with the attribute specified by its key
     * added.
     *
     * @param attributeKey the attribute key
     * @param value        the value of the attribute
     * @return the new instance with the attribute added
     */
    def withAttribute(attributeKey: AttributeKey, value: String): Attributes =
      copy(values = values + (attributeKey -> value))
  }

  /**
   * A data class representing a WebDav folder.
   *
   * While the default attributes are stored directly in member fields,
   * arbitrary additional attributes are hold in an [[Attributes]] object.
   *
   * @param id             the ID of this folder (represented by a relative URI)
   * @param name           the name of this folder
   * @param description    a description of this folder
   * @param createdAt      the date when this folder was created
   * @param lastModifiedAt the date when this folder was modified the last time
   * @param attributes     an object with additional attributes
   */
  case class DavFolder(override val id: Uri,
                       override val name: String,
                       override val description: Option[String],
                       override val createdAt: Instant,
                       override val lastModifiedAt: Instant,
                       attributes: Attributes) extends Model.Folder[Uri]

  /**
   * A data class representing a WebDav file.
   * While the default attributes are stored directly in member fields,
   * arbitrary additional attributes are hold in an [[Attributes]] object.
   *
   * @param id             the ID of this folder (represented by a relative URI)
   * @param name           the name of this folder
   * @param description    a description of this folder
   * @param createdAt      the date when this folder was created
   * @param lastModifiedAt the date when this folder was modified the last time
   * @param size           the size of this file
   * @param attributes     an object with additional attributes
   */
  case class DavFile(override val id: Uri,
                     override val name: String,
                     override val description: Option[String],
                     override val createdAt: Instant,
                     override val lastModifiedAt: Instant,
                     override val size: Long,
                     attributes: Attributes) extends Model.File[Uri]

  /**
   * An empty ''Attributes'' instances, i.e. an instance that contains no
   * custom attributes.
   */
  final val EmptyAttributes: Attributes = Attributes(Map.empty)

  /**
   * Constructs a new ''DavFolder'' object based on the properties provided.
   * This function can be used for instance to create a folder object that is
   * passed to the ''createFolder()'' or ''updateFolder()'' functions of a
   * [[DavFileSystem]] service. It expects all mandatory attributes of the
   * folder, provides default values for optional attributes, and ignores the
   * ones that are not evaluated when creating or updating a folder. (Some
   * properties like the creation date are set by the server and can usually
   * not be overridden.)
   *
   * @param name        the name of the folder
   * @param id          the optional folder ID (its URI)
   * @param description an optional description
   * @param attributes  optional custom attributes
   * @return the new ''DavFolder'' object
   */
  def newFolder(name: String, id: Uri = null, description: Option[String] = None,
                attributes: Attributes = EmptyAttributes):
  DavFolder =
    DavFolder(name = name, description = description, attributes = attributes, id = id, createdAt = null,
      lastModifiedAt = null)

  /**
   * Constructs a new ''DavFile'' object based on the properties provided.
   * This function can be used for instance to create a file object that is
   * passed to the ''createFile()'' or ''updateFile()'' functions of a
   * [[DavFileSystem]] service. It expects all mandatory attributes of the
   * file, provides default values for optional attributes, and ignores the
   * ones that not evaluated when creating or updating a file. (Some properties
   * like the creation date are set by the server and can usually not be
   * overridden.)
   *
   * @param name        the name of the file
   * @param size        the size of the file's content
   * @param id          the optional file ID (its URI)
   * @param description an optional description
   * @param attributes  optional custom attributes
   * @return the new ''DavFile'' object
   */
  def newFile(name: String, size: Long, id: Uri = null, description: Option[String] = None,
              attributes: Attributes = EmptyAttributes): DavFile =
    DavFile(name = name, size = size, description = description, attributes = attributes,
      id = id, createdAt = null, lastModifiedAt = null)
}
