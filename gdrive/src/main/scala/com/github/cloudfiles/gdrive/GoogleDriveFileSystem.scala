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

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse, MediaRange, MediaTypes, Uri}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode

import scala.concurrent.Future
import scala.concurrent.duration._

object GoogleDriveFileSystem {
  /** The URI prefix for accessing the /files resource. */
  private val FileResourcePrefix = "/drive/v3/files/"

  /**
   * Constant for the names of the fields that are requested for files.
   */
  private val FileFields = "id,name,size,createdTime,modifiedTime,mimeType,parents,properties,appProperties," +
    "md5Checksum,description"

  /** The header for accepting JSON responses. */
  private val AcceptJsonHeader = Accept(MediaRange(MediaTypes.`application/json`))

  /** A sequence with the standard headers to send for typical requests. */
  private val StdHeaders = List(AcceptJsonHeader)

  /** The query parameter to selecting the fields to retrieve. */
  private val QueryParamFields = "fields"

  /**
   * The map with query parameters to be used when resolving a file. Here a
   * parameter needs to be passed to select the required properties.
   */
  private val QueryParamsFileFields = Map(QueryParamFields -> FileFields)

  /** The special mime type used to identify a folder in GoogleDrive. */
  private val MimeTypeFolder = "application/vnd.google-apps.folder"
}

/**
 * The GoogleDrive-specific implementation of the
 * [[com.github.cloudfiles.core.FileSystem]] trait.
 *
 * This class sends HTTP requests against the GoogleDrive API to manipulate
 * files and folders. Note that the configuration allows setting a custom URI
 * (which should normally only be necessary for tests), but there is no
 * property to select a drive of a specific user. This information is contained
 * in the credentials (typically an OAuth token) managed by the request actor.
 *
 * GoogleDrive has an organization, which in some ways differs from typical
 * hierarchical file systems. A single element can have multiple parents, and
 * there is, therefore, no concept of a unique path to a specific element. This
 * makes the ''resolvePath()'' operation quite expensive, because it has to
 * navigate through all the folders starting from the root folder. Even
 * resolving the root ID is not trivial if a root path has been configured. So,
 * the most efficient way to use this ''FileSystem'' implementation is to make
 * use of element IDs as far as possible.
 *
 * @param config the configuration for this ''FileSystem''
 */
class GoogleDriveFileSystem(val config: GoogleDriveConfig)
  extends ExtensibleFileSystem[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder,
    Model.FolderContent[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder]] {

  import GoogleDriveFileSystem._

  private implicit val requestTimeout: Timeout = Timeout(1.minute)

  override def patchFolder(source: Model.Folder[String], spec: ElementPatchSpec): GoogleDriveModel.GoogleDriveFolder = ???

  override def patchFile(source: Model.File[String], spec: ElementPatchSpec): GoogleDriveModel.GoogleDriveFile = ???

  override def resolvePath(path: String)(implicit system: ActorSystem[_]): FileSystem.Operation[String] = ???

  override def rootID(implicit system: ActorSystem[_]): Operation[String] = Operation {
    _ => Future.successful("root")
  }

  override def resolveFile(id: String)(implicit system: ActorSystem[_]): Operation[GoogleDriveModel.GoogleDriveFile] =
    resolveElement(id) map {
      case file: GoogleDriveModel.GoogleDriveFile => file
      case _ => throw new IllegalArgumentException(s"Could not resolve element '$id' to a file; it is a folder.")
    }

  override def resolveFolder(id: String)(implicit system: ActorSystem[_]):
  Operation[GoogleDriveModel.GoogleDriveFolder] =
    resolveElement(id) map {
      case folder: GoogleDriveModel.GoogleDriveFolder => folder
      case _ => throw new IllegalArgumentException(s"Could not resolve element '$id' to a folder; it is a file.")
    }

  override def folderContent(id: String)(implicit system: ActorSystem[_]): FileSystem.Operation[Model.FolderContent[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder]] = ???

  override def createFolder(parent: String, folder: Model.Folder[String])(implicit system: ActorSystem[_]): FileSystem.Operation[String] = ???

  override def updateFolder(folder: Model.Folder[String])(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  override def deleteFolder(folderID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElement(folderID)

  override def createFile(parent: String, file: Model.File[String], content: Source[ByteString, Any])(implicit system: ActorSystem[_]): FileSystem.Operation[String] = ???

  override def updateFile(file: Model.File[String])(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  override def updateFileContent(fileID: String, size: Long, content: Source[ByteString, Any])(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  override def downloadFile(fileID: String)(implicit system: ActorSystem[_]): FileSystem.Operation[HttpEntity] = ???

  override def deleteFile(fileID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElement(fileID)

  /**
   * Returns an operation that requests the Google File with the given ID and
   * converts it to an [[GoogleDriveModel.GoogleDriveElement]], based on its
   * mime type.
   *
   * @param id     the ID of the element in question
   * @param system the actor system
   * @return the operation that resolves this element
   */
  private def resolveElement(id: String)(implicit system: ActorSystem[_]):
  Operation[GoogleDriveModel.GoogleDriveElement] = Operation {
    httpSender =>
      val uri = Uri(s"$FileResourcePrefix$id").withQuery(Uri.Query(QueryParamsFileFields))
      val request = HttpRequest(uri = uri, headers = StdHeaders)
      executeQuery[GoogleDriveJsonProtocol.File](httpSender, request) map { googleFile =>
        googleFile.mimeType match {
          case MimeTypeFolder => GoogleDriveModel.GoogleDriveFolder(googleFile)
          case _ => GoogleDriveModel.GoogleDriveFile(googleFile)
        }
      }
  }

  /**
   * Returns an operation that deletes the element with the given ID.
   *
   * @param id     the ID of the element to delete
   * @param system the actor system
   * @return the operation to delete this element
   */
  private def deleteElement(id: String)(implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      val deleteRequest = HttpRequest(method = HttpMethods.DELETE, uri = s"$FileResourcePrefix$id")
      executeUpdate(httpSender, deleteRequest)
  }

  /**
   * Helper function to execute an HTTP request that is expected to yield a
   * JSON response, which is to be converted to an object.
   *
   * @param httpSender the request sender actor
   * @param request    the request to be sent
   * @param system     the actor system
   * @param um         the object to unmarshal the result
   * @tparam R the result type
   * @return a future with the unmarshalled object
   */
  private def executeQuery[R](httpSender: ActorRef[HttpRequestSender.HttpCommand], request: HttpRequest)
                             (implicit system: ActorSystem[_], um: Unmarshaller[HttpResponse, R]): Future[R] =
    for {
      result <- HttpRequestSender.sendRequestSuccess(httpSender, request, null, DiscardEntityMode.OnFailure)
      obj <- Unmarshal(result.response).to[R]
    } yield obj

  /**
   * Convenience function to execute an HTTP request with default parameters.
   *
   * @param httpSender  the actor for sending requests
   * @param request     the request to be sent
   * @param discardMode the entity discard mode (most requests are updates,
   *                    where the result is irrelevant; therefore, entities are
   *                    per default discarded)
   * @param system      the actor system
   * @return a ''Future'' with the result of the request
   */
  private def executeUpdate(httpSender: ActorRef[HttpRequestSender.HttpCommand], request: HttpRequest,
                            discardMode: DiscardEntityMode = DiscardEntityMode.Always)
                           (implicit system: ActorSystem[_]): Future[Unit] =
    HttpRequestSender.sendRequestSuccess(httpSender, request, requestData = null, discardMode = discardMode)
      .map(_ => ())
}
