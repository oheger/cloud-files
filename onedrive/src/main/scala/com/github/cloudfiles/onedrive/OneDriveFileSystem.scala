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

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object OneDriveFileSystem {
  /** The prefix to select the root path of a drive. */
  private val RootPrefix = "/root"

  /** A query parameter to select only the ID field of a drive item. */
  private val SelectIDParam = "select=id"
}

/**
 * The OneDrive-specific implementation of the [[FileSystem]] trait.
 *
 * This class implements operations on files and folders located on a OneDrive
 * by sending REST requests against the OneDrive API, whose root URI is defined
 * in the configuration. The class is optimized to address OneDrive items based
 * on their string ID. IDs can be obtained from the ''rootID()'' or
 * ''resolvePath()'' functions, which use path-based addressing (refer to
 * https://docs.microsoft.com/en-us/onedrive/developer/rest-api/concepts/addressing-driveitems?view=odsp-graph-online).
 * Both of these functions take the configured root path into account.
 *
 * @param config the configuration
 */
class OneDriveFileSystem(config: OneDriveConfig)
  extends FileSystem[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder,
    Model.FolderContent[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]] {

  import OneDriveFileSystem._

  /** The URI prefix pointing to the root of the current drive. */
  private val rootUriPrefix =
    s"${UriEncodingHelper.removeTrailingSeparator(config.serverUri)}/${config.driveID}$RootPrefix"

  /** A prefix for URIs to resolve paths in this file system. */
  private val resolveUriPrefix = createResolvePrefix()

  /** Stores the URI to the root folder used by ''rootID()''. */
  private val rootUri = createRootUri()

  override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[String] = {
    val uri = Uri(resolveUriPrefix + UriEncodingHelper.withLeadingSeparator(path) + ":")
      .withQuery(Uri.Query(SelectIDParam))
    resolveUriOperation(uri)
  }

  override def rootID(implicit system: ActorSystem[_]): Operation[String] = resolveUriOperation(rootUri)

  /**
   * Returns the file identified by the given ID.
   *
   * @param id     the ID of the file in question
   * @param system the actor system
   * @return the ''Operation'' returning the file with this ID
   */
  override def resolveFile(id: String)(implicit system: ActorSystem[_]): FileSystem.Operation[OneDriveModel.OneDriveFile] = ???

  /**
   * Returns the folder identified by the given ID.
   *
   * @param id     the ID of the folder in question
   * @param system the actor system
   * @return the ''Operation'' returning the folder with this ID
   */
  override def resolveFolder(id: String)(implicit system: ActorSystem[_]): FileSystem.Operation[OneDriveModel.OneDriveFolder] = ???

  /**
   * Returns an object describing the content of the folder with the given ID.
   * This can be used to find the files and the folders that are the children
   * of the folder with the ID specified.
   *
   * @param id     the ID of the folder in question
   * @param system the actor system
   * @return the ''Operation'' returning the content of this folder
   */
  override def folderContent(id: String)(implicit system: ActorSystem[_]): FileSystem.Operation[Model.FolderContent[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]] = ???

  /**
   * Creates a folder as a child of the given parent folder. The attributes of
   * the new folder are defined by the folder object provided. If the operation
   * is successful, the ID of the new folder is returned.
   *
   * @param parent the ID of the parent folder
   * @param folder an object with the attributes of the new folder
   * @param system the actor system
   * @return the ''Operation'' to create a new folder
   */
  override def createFolder(parent: String, folder: Model.Folder[String])(implicit system: ActorSystem[_]): FileSystem.Operation[String] = ???

  /**
   * Updates the metadata of a folder. The passed in folder object must contain
   * the ID of the folder affected and the attributes to update.
   *
   * @param folder the object representing the folder
   * @param system the actor system
   * @return the ''Operation'' to update folder metadata
   */
  override def updateFolder(folder: Model.Folder[String])(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  /**
   * Deletes the folder with the given ID. Note that depending on the concrete
   * implementation, it may be required that a folder is empty before it can be
   * deleted. The root folder can typically not be deleted.
   *
   * @param folderID the ID of the folder to delete
   * @param system   the actor system
   * @return the ''Operation'' to delete a folder
   */
  override def deleteFolder(folderID: String)(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  /**
   * Creates a file as a child of the given parent folder by uploading the
   * file's content and setting some metadata attributes. The attributes to set
   * are provided in form of a file object; typically some properties are
   * mandatory, such as the file name and the file size. If the operation is
   * successful, the ID of the new file is returned.
   *
   * @param parent  the ID of the parent folder
   * @param file    an object with the attributes of the new file
   * @param content a ''Source'' with the content of the file
   * @param system  the actor system
   * @return the ''Operation'' to create a new file
   */
  override def createFile(parent: String, file: Model.File[String], content: Source[ByteString, Any])(implicit system: ActorSystem[_]): FileSystem.Operation[String] = ???

  /**
   * Updates the metadata of a file. The passed in file object must contain the
   * ID of the file affected and the attributes to set.
   *
   * @param file   the object representing the file
   * @param system the actor system
   * @return the ''Operation'' to update file metadata
   */
  override def updateFile(file: Model.File[String])(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  /**
   * Updates the content of a file by uploading new data to the server.
   *
   * @param fileID  the ID of the file affected
   * @param size    the size of the new content
   * @param content a ''Source'' with the content of the file
   * @param system  the actor system
   * @return the ''Operation'' to upload new file content
   */
  override def updateFileContent(fileID: String, size: Int, content: Source[ByteString, Any])(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  /**
   * Returns an entity for downloading the content of a file. The download can
   * be performed by creating a stream with the data bytes of the resulting
   * entity. Note that the entity must be consumed in all cases, either by
   * running the stream with the data bytes or by discarding the bytes.
   *
   * @param fileID the ID of the file affected
   * @param system the actor system
   * @return the ''Operation'' to obtain an entity for downloading a file
   */
  override def downloadFile(fileID: String)(implicit system: ActorSystem[_]): FileSystem.Operation[HttpEntity] = ???

  /**
   * Deletes the file with the given ID.
   *
   * @param fileID the ID of the file to delete
   * @param system the actor system
   * @return the ''Operation'' to delete the file
   */
  override def deleteFile(fileID: String)(implicit system: ActorSystem[_]): FileSystem.Operation[Unit] = ???

  /**
   * Returns an operation that resolves the specified URI in this file system.
   * The given URI is fetched via a GET request, from the resulting response
   * the ID field is extracted.
   *
   * @param uri    the URI to request
   * @param system the actor system
   * @return the operation to resolve this URI
   */
  private def resolveUriOperation(uri: Uri)(implicit system: ActorSystem[_]): Operation[String] = Operation {
    httpSender =>
      val rootRequest = HttpRequest(uri = uri)
      executeJsonRequest[ResolveResponse](httpSender, rootRequest).map(_.id)
  }

  /**
   * Helper function to execute a request an HTTP request that is expected to
   * yield a JSON response, which is to be converted to an object.
   *
   * @param httpSender the request sender actor
   * @param request    the request to be sent
   * @param system     the actor system
   * @param um         the object to unmarshal the result
   * @tparam R the result type
   * @return a future with the unmarshalled object
   */
  private def executeJsonRequest[R](httpSender: ActorRef[HttpRequestSender.HttpCommand], request: HttpRequest)
                                   (implicit system: ActorSystem[_], um: Unmarshaller[HttpResponse, R]): Future[R] =
    for {
      result <- HttpRequestSender.sendRequestSuccess(httpSender, request, null)
      obj <- Unmarshal(result.response).to[R]
    } yield obj

  /**
   * Provides the timeout for HTTP requests in implicit scope from the
   * configuration of this file system.
   *
   * @param system the actor system
   * @return the timeout for HTTP requests
   */
  private implicit def fetchTimeout(implicit system: ActorSystem[_]): Timeout = config.timeout

  /**
   * Provides the execution context for handling futures in implicit scope.
   * This service uses the context from the actor system.
   *
   * @param system the actor system
   * @return the ''ExecutionContext''
   */
  private implicit def executionContext(implicit system: ActorSystem[_]): ExecutionContext = system.executionContext

  /**
   * Generates the URI to access the root drive item for the current drive. If
   * no root path is specified, this URI points to the root of the drive;
   * otherwise, it selects this root path.
   *
   * @return the URI to get the root drive item
   */
  private def createRootUri(): Uri =
    Uri(createUriWithOptionalRootPath(rootUriPrefix)(p => s"$rootUriPrefix:$p:"))
      .withQuery(Uri.Query(SelectIDParam))

  /**
   * Generates the prefix of an URI to resolve a path in this file system. The
   * prefix depends on the presence of a root path.
   *
   * @return the prefix to resolve paths
   */
  private def createResolvePrefix(): String =
    createUriWithOptionalRootPath(rootUriPrefix + ":")(p => s"$rootUriPrefix:$p")

  /**
   * Helper function to generate a URI string that depends on the presence of
   * a root path. If no root path is defined, the ''noRoot'' parameter is
   * evaluated. Otherwise, the given function is invoked passing in the
   * normalized root path.
   *
   * @param noRoot return value if no root path is set
   * @param f      the function to generate the result with the root path
   * @return the resulting URI
   */
  private def createUriWithOptionalRootPath(noRoot: => String)(f: String => String): String =
    config.optRootPath.fold(noRoot)(root => f(UriEncodingHelper withLeadingSeparator root))
}
