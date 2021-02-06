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

import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, RootJsonFormat, deserializationError}

import java.time.Instant

/**
 * A module defining data classes that correspond to the OneDrive REST API as
 * described at
 * https://docs.microsoft.com/en-us/onedrive/developer/rest-api/?view=odsp-graph-online.
 *
 * The OneDrive REST API uses JSON as data exchange format. The classes defined
 * here are used to read and write JSON to and from Scala objects.
 *
 * In addition, the module defines converters for these data classes to enable
 * a proper JSON serialization. They are available as implicits, which must be
 * available in the current scope to make the conversion to and from JSON
 * possible.
 */
object OneDriveJsonProtocol extends DefaultJsonProtocol {

  /**
   * A data class defining several hash values for files. An instance of this
   * class is part of a OneDrive item that is a file.
   *
   * @param crc32Hash    CRC32 checksum as hex string
   * @param sha1Hash     SHA1 hash as hex string
   * @param quickXorHash quick XOR hash as Base64-encoded string
   */
  case class Hashes(crc32Hash: Option[String],
                    sha1Hash: Option[String],
                    quickXorHash: Option[String])

  /**
   * A data class representing an identity. This is used to store information
   * about actors that did something with drive items.
   *
   * @param id          the ID of this identity
   * @param displayName a human-readable name of this identity
   */
  case class Identity(id: String,
                      displayName: Option[String])

  case class IdentitySet(application: Option[Identity],
                         device: Option[Identity],
                         group: Option[Identity],
                         user: Option[Identity])

  /**
   * A data class representing file information. Drive items of type file contain
   * this structure.
   *
   * @param mimeType the auto-discovered mime type
   * @param hashes   a structure with hashes of the file's content
   */
  case class File(mimeType: String,
                  hashes: Hashes)

  /**
   * A data class representing information about a folder. Drive items of type
   * folder contain this structure.
   *
   * @param childCount the number of children of this folder
   */
  case class Folder(childCount: Int)

  /**
   * A data class storing information related to the local file system of this
   * drive item. In contrast to similar properties of the drive item structure,
   * this data is independent on operations done via OneDrive. It can be
   * updated by applications.
   *
   * @param createdDateTime      the time of creation
   * @param lastAccessedDateTime the time of the last access (in recent file
   *                             list only)
   * @param lastModifiedDateTime the time of the last modification
   */
  case class FileSystemInfo(createdDateTime: Instant,
                            lastModifiedDateTime: Instant,
                            lastAccessedDateTime: Option[Instant])

  /**
   * A data class representing a reference to another drive item.
   *
   * @param id        the target ID
   * @param driveId   the target drive ID
   * @param driveType the type of the drive
   * @param listId    list ID
   * @param name      the name of the target item
   * @param path      the path of the target item
   * @param shareId   ID of a shared resource
   * @param siteId    identifier of the site
   */
  case class ItemReference(id: String,
                           driveId: String,
                           driveType: String,
                           listId: Option[String],
                           name: Option[String],
                           path: Option[String],
                           shareId: Option[String],
                           siteId: Option[String])

  /**
   * A data class holding information about a drive item that has been shared.
   *
   * @param owner          identity of the owner of the item
   * @param scope          the scope how the item is shared
   * @param sharedBy       the identity of the user who shared the item
   * @param sharedDateTime the time when the item was shared
   */
  case class Shared(owner: IdentitySet,
                    scope: String,
                    sharedBy: IdentitySet,
                    sharedDateTime: Instant)

  /**
   * A data class describing a special folder. This information is available
   * for drive items if they represent one of the special folders.
   *
   * @param name the name of this special folder
   */
  case class SpecialFolder(name: String)

  /**
   * A data class representing a drive item.
   *
   * Depending on the item type, specific facets are present or not. Whether
   * the item is a file or a folder is determined by the presence or absence of
   * the ''file'' and ''folder'' facets. Special folders are indicated by the
   * presence of the [[SpecialFolder]] facet.
   *
   * Many of these properties are read-only and are set by OneDrive.
   *
   * @param id                   the ID of this item
   * @param createdBy            identity of the actor that created this item
   * @param createdDateTime      the time when this item was created
   * @param lastModifiedBy       identity of the actor that modified this item
   * @param lastModifiedDateTime the time of the last modification
   * @param name                 the name of this item
   * @param description          a description of this item
   * @param size                 the size of this item
   * @param webUrl               the URL to display this item in a browser
   * @param file                 a facet with file-related information
   * @param folder               a facet with folder-related information
   * @param fileSystemInfo       local file system information
   * @param parentReference      the reference to the parent item
   * @param shared               information about the sharing state
   * @param specialFolder        a facet to indicate a special folder
   */
  case class DriveItem(id: String,
                       createdBy: IdentitySet,
                       createdDateTime: Instant,
                       lastModifiedBy: IdentitySet,
                       lastModifiedDateTime: Instant,
                       name: String,
                       description: Option[String],
                       size: Long,
                       webUrl: String,
                       file: Option[File],
                       folder: Option[Folder],
                       fileSystemInfo: FileSystemInfo,
                       parentReference: Option[ItemReference],
                       shared: Option[Shared],
                       specialFolder: Option[SpecialFolder])

  /**
   * A data class storing information related to the local file system for a
   * drive item that is going to be created or updated. The user can fill in
   * the fields that should be updated and leave the others undefined.
   *
   * @param createdDateTime      the local creation time of the item
   * @param lastModifiedDateTime the local modified time of the item
   * @param lastAccessedDateTime the access time (only in last-access list)
   */
  case class WritableFileSystemInfo(createdDateTime: Option[Instant] = None,
                                    lastModifiedDateTime: Option[Instant] = None,
                                    lastAccessedDateTime: Option[Instant] = None)

  /**
   * A data class used to generate an empty marker object in a JSON request.
   * Such empty objects are needed for some use cases in OneDrive, e.g. to mark
   * an item as a folder or a file. The intended usage is to have the optional
   * property of an instance always be ''None'', so that no properties are
   * generated.
   *
   * @param optValue the value, which typically should be undefined
   */
  case class MarkerProperty(optValue: Option[String] = None)

  /**
   * A data class representing a drive item that is going to be newly created
   * or updated. For such use cases, only a subset of the properties of an
   * item can be written; these are defined here.
   *
   * @param name           the name of the item
   * @param description    the description
   * @param fileSystemInfo information about the local file system
   * @param file           marker object whether this is a file
   * @param folder         marker object whether this is a folder
   */
  case class WritableDriveItem(name: Option[String] = None,
                               description: Option[String] = None,
                               fileSystemInfo: Option[WritableFileSystemInfo] = None,
                               file: Option[MarkerProperty] = None,
                               folder: Option[MarkerProperty] = None)

  /**
   * A data class representing the response from the OneDrive server for a
   * request to resolve a drive element.
   *
   * @param id the ID that has been resolved
   */
  case class ResolveResponse(id: String)

  /**
   * A data class representing the OneDrive JSON response for a folder request.
   * Here we are only interested in the list with the child items of the
   * current folder. For large folders, the content is distributed over multiple
   * pages. In this case, a link for the next page is available.
   *
   * @param value    a list with the child items of this folder
   * @param nextLink an optional link to the next page
   */
  case class FolderResponse(value: List[DriveItem], nextLink: Option[String])

  /**
   * A data class to represent the response of a request to upload a chunk of a
   * file. The upload response is different for the first chunks and the last
   * chunk. For the last chunk, a ''DriveItem'' representation is sent, but we
   * are only interested in the ID. The previous responses are irrelevant, so
   * they are just ignored.
   *
   * @param id the ID of the item extracted from the response
   */
  case class UploadChunkResponse(id: Option[String])

  /**
   * A data class to represent the request for an upload session.
   *
   * @param item the item for which content is uploaded
   */
  case class UploadSessionRequest(item: WritableDriveItem)

  /**
   * A data class representing the response of an upload session request. We
   * are only interested in the URL where the content needs to be uploaded.
   *
   * @param uploadUrl the URL to use for the following upload
   */
  case class UploadSessionResponse(uploadUrl: String)

  /**
   * A format implementation to deal with date-time values. OneDrive uses the
   * default ISO format that can be parsed by the ''Instant'' class. Therefore,
   * this implementation is straight-forward.
   */
  implicit object InstantFormat extends JsonFormat[Instant] {
    override def read(json: JsValue): Instant = json match {
      case JsString(value) => Instant.parse(value)
      case j => deserializationError(s"Expected a string for an instant, but was: $j.")
    }

    override def write(obj: Instant): JsValue = JsString(obj.toString)
  }

  implicit val hashesFormat: RootJsonFormat[Hashes] = jsonFormat3(Hashes)
  implicit val identityFormat: RootJsonFormat[Identity] = jsonFormat2(Identity)
  implicit val identitySetFormat: RootJsonFormat[IdentitySet] = jsonFormat4(IdentitySet)
  implicit val fileFormat: RootJsonFormat[File] = jsonFormat2(File)
  implicit val folderFormat: RootJsonFormat[Folder] = jsonFormat1(Folder)
  implicit val fileSystemInfoFormat: RootJsonFormat[FileSystemInfo] = jsonFormat3(FileSystemInfo)
  implicit val itemReferenceFormat: RootJsonFormat[ItemReference] = jsonFormat8(ItemReference)
  implicit val sharedFormat: RootJsonFormat[Shared] = jsonFormat4(Shared)
  implicit val specialFolderFormat: RootJsonFormat[SpecialFolder] = jsonFormat1(SpecialFolder)
  implicit val driveItemFormat: RootJsonFormat[DriveItem] = jsonFormat15(DriveItem)
  implicit val writableFileSystemInfoFormat: RootJsonFormat[WritableFileSystemInfo] =
    jsonFormat3(WritableFileSystemInfo)
  implicit val markerPropertyFormat: RootJsonFormat[MarkerProperty] = jsonFormat1(MarkerProperty)
  implicit val writableDriveItemFormat: RootJsonFormat[WritableDriveItem] = jsonFormat5(WritableDriveItem)
  implicit val resolveResponseFormat: RootJsonFormat[ResolveResponse] = jsonFormat1(ResolveResponse)
  implicit val folderResponseFormat: RootJsonFormat[FolderResponse] = {
    jsonFormat(FolderResponse.apply, "value", "@odata.nextLink")
  }
  implicit val uploadChunkResponseFormat: RootJsonFormat[UploadChunkResponse] = jsonFormat1(UploadChunkResponse)
  implicit val uploadSessionRequest: RootJsonFormat[UploadSessionRequest] = jsonFormat1(UploadSessionRequest)
  implicit val uploadSessionResponse: RootJsonFormat[UploadSessionResponse] = jsonFormat1(UploadSessionResponse)
}
