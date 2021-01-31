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
import akka.http.scaladsl.marshalling.{Marshal, Marshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object OneDriveFileSystem {
  /** The prefix to select the root path of a drive. */
  private val RootPrefix = "/root"

  /** The suffix to select the children of a drive item. */
  private val ChildrenSuffix = "/children"

  /** A query parameter to select only the ID field of a drive item. */
  private val SelectIDParam = "select=id"

  /** The property to generate marker objects in JSON. */
  private val Marker = OneDriveJsonProtocol.MarkerProperty()

  /**
   * Transforms the given ''DriveItem'' object to a ''WritableDriveItem'' that
   * can be used for create or update operations. The resulting object contains
   * only properties that can be written and that are defined in the source
   * object.
   *
   * @param item the ''DriveItem''
   * @return the corresponding ''WritableDriveItem''
   */
  private def toWritableItem(item: DriveItem): WritableDriveItem =
    OneDriveJsonProtocol.WritableDriveItem(name = Option(item.name),
      description = item.description, fileSystemInfo = toWritableFileSystemInfo(item.fileSystemInfo),
      folder = item.folder map (_ => Marker), file = item.file map (_ => Marker))

  /**
   * Transforms the given ''FileSystemInfo'' object to a
   * ''WritableFileSystemInfo'' that contains only defined properties. Result
   * is an empty option if no properties are defined.
   *
   * @param info the ''FileSystemInfo''
   * @return an option with the corresponding ''WritableFileSystemInfo''
   */
  private def toWritableFileSystemInfo(info: FileSystemInfo): Option[WritableFileSystemInfo] =
    Option(info) map { fsi =>
      WritableFileSystemInfo(createdDateTime = Option(fsi.createdDateTime),
        lastModifiedDateTime = Option(fsi.lastModifiedDateTime),
        lastAccessedDateTime = fsi.lastAccessedDateTime)
    }

  /**
   * Returns a ''DriveItem'' that represents the passed in element. If the
   * element is already a [[OneDriveModel.OneDriveElement]], the item can be
   * extracted from there. Otherwise, a new item has to be created.
   *
   * @param element the element in question
   * @return a ''DriveItem'' describing this element
   */
  private def itemFor(element: Model.Element[String]): DriveItem =
    element match {
      case e: OneDriveModel.OneDriveElement => e.item
      case folder: Model.Folder[String] => OneDriveModel.newFolder(folder.name, folder.description).item
      case file: Model.File[String] => OneDriveModel.newFile(file.name, file.description).item
    }
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
 * Note that some operation against the OneDrive file system require
 * interaction with multiple servers. For instance, when downloading a file,
 * the client is typically redirected to another server, from which the file's
 * content can be downloaded. The actor passed to the functions for executing
 * HTTP requests must support this.
 *
 * @param config the configuration
 */
class OneDriveFileSystem(config: OneDriveConfig)
  extends FileSystem[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder,
    Model.FolderContent[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]] {

  import OneDriveFileSystem._

  /** The base URI for accessing the OneDrive API for the selected drive. */
  private val baseUri = s"${UriEncodingHelper.removeTrailingSeparator(config.serverUri)}/${config.driveID}"

  /** The URI prefix pointing to the root of the current drive. */
  private val rootUriPrefix = s"$baseUri$RootPrefix"

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

  override def resolveFile(id: String)(implicit system: ActorSystem[_]): Operation[OneDriveModel.OneDriveFile] =
    resolveItem[OneDriveModel.OneDriveFile](id)

  override def resolveFolder(id: String)(implicit system: ActorSystem[_]): Operation[OneDriveModel.OneDriveFolder] =
    resolveItem[OneDriveModel.OneDriveFolder](id)

  override def folderContent(id: String)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]] = Operation {
    httpSender =>
      fetchFolderContent(httpSender, id, s"${itemUri(id)}$ChildrenSuffix", Map.empty, Map.empty)
  }

  override def createFolder(parent: String, folder: Model.Folder[String])(implicit system: ActorSystem[_]):
  Operation[String] = Operation {
    httpSender =>
      val createUrl = itemUri(parent) + ChildrenSuffix
      val writableItem = toWritableItem(itemFor(folder))
      for {
        entity <- prepareJsonRequestEntity(writableItem)
        request = HttpRequest(method = HttpMethods.POST, uri = createUrl, entity = entity)
        response <- executeJsonRequest[DriveItem](httpSender, request)
      } yield response.id
  }

  override def updateFolder(folder: Model.Folder[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(folder)

  override def deleteFolder(folderID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteItem(folderID)

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

  override def updateFile(file: Model.File[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(file)

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

  override def downloadFile(fileID: String)(implicit system: ActorSystem[_]): Operation[HttpEntity] = Operation {
    httpSender =>
      val downloadUriRequest = HttpRequest(uri = itemUri(fileID) + "/content")
      for {
        uriResult <- executeRequest(httpSender, downloadUriRequest)
        downloadResult <- sendDownloadRequest(httpSender, uriResult)
      } yield downloadResult.response.entity
  }

  override def deleteFile(fileID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteItem(fileID)

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
   * Returns an operation to resolve the drive item identified by its ID and
   * map it to a destination type.
   *
   * @param id     the ID of the item to resolve
   * @param system the actor system
   * @param ct     the class tag for the destination type
   * @tparam A the result type
   * @return the operation to resolve the item
   */
  private def resolveItem[A](id: String)
                            (implicit system: ActorSystem[_], ct: ClassTag[A]): Operation[A] = Operation {
    httpSender =>
      val folderRequest = HttpRequest(uri = itemUri(id))
      executeJsonRequest[DriveItem](httpSender, folderRequest)
        .map(createElement)
        .map { elem =>
          if (!ct.runtimeClass.isInstance(elem))
            throw new IllegalArgumentException(s"Element with ID $id can be resolved, but is not of the expected " +
              s"type ${ct.runtimeClass}.")
          else elem
        }.mapTo[A]
  }

  /**
   * Creates an element of the correct type that wraps the given ''DriveItem''.
   *
   * @param item the ''DriveItem''
   * @return the element wrapping this item
   */
  private def createElement(item: OneDriveJsonProtocol.DriveItem): OneDriveModel.OneDriveElement =
    if (item.folder.isDefined) OneDriveModel.OneDriveFolder(item)
    else OneDriveModel.OneDriveFile(item)

  /**
   * Retrieves the content of a folder that may be split over multiple pages.
   * For each page a request is sent and the child elements are constructed.
   * If the response indicates that another page is available, the function
   * calls itself recursively to process it.
   *
   * @param httpSender the actor for sending HTTP requests
   * @param id         the ID of the folder to retrieve
   * @param uri        the URI for the next page to load
   * @param files      the map with files that have been retrieved so far
   * @param folders    the map with folders that have been retrieved so far
   * @param system     the actor system
   * @return a future with the content of the folder
   */
  private def fetchFolderContent(httpSender: ActorRef[HttpRequestSender.HttpCommand], id: String, uri: String,
                                 files: Map[String, OneDriveModel.OneDriveFile],
                                 folders: Map[String, OneDriveModel.OneDriveFolder])
                                (implicit system: ActorSystem[_]):
  Future[Model.FolderContent[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]] = {
    val contentRequest = HttpRequest(uri = uri)
    executeJsonRequest[FolderResponse](httpSender, contentRequest) flatMap { response =>
      val contentMaps = response.value.foldLeft((files, folders)) { (maps, item) =>
        createElement(item) match {
          case folder: OneDriveModel.OneDriveFolder =>
            (maps._1, maps._2 + (item.id -> folder))
          case file: OneDriveModel.OneDriveFile =>
            (maps._1 + (item.id -> file), maps._2)
        }
      }

      response.nextLink match {
        case Some(link) =>
          fetchFolderContent(httpSender, id, link, contentMaps._1, contentMaps._2)
        case None =>
          Future.successful(Model.FolderContent(folderID = id, files = contentMaps._1, folders = contentMaps._2))
      }
    }
  }

  /**
   * Returns an operation to delete the ''DriveItem'' with the given ID. This
   * is the same for files and folders.
   *
   * @param id     the ID of the element to delete
   * @param system the actor system
   * @return the operation to delete this element
   */
  private def deleteItem(id: String)(implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      executeRequest(httpSender, HttpRequest(method = HttpMethods.DELETE, uri = itemUri(id)))
        .map(_ => ())
  }

  /**
   * Returns an operation to update the given element, which can be either a
   * folder or a file. All the properties defined for the ''DriveItem''
   * associated with the element get updated.
   *
   * @param element the element to be updated
   * @param system  the actor system
   * @return the operation to update this element
   */
  private def updateElement(element: Model.Element[String])(implicit system: ActorSystem[_]):
  Operation[Unit] = Operation {
    httpSender =>
      val writableItem = toWritableItem(itemFor(element))
      for {
        entity <- prepareJsonRequestEntity(writableItem)
        request = HttpRequest(method = HttpMethods.PATCH, uri = itemUri(element.id), entity = entity)
        _ <- executeJsonRequest[DriveItem](httpSender, request)
      } yield ()
  }

  /**
   * Sends the request to actually download a file. File downloads are a
   * two-step process: First the URI from which to download the file has to be
   * obtained; second, a GET to this URI has to be executed. This function
   * performs the second step and expects the result of the first step as
   * argument.
   *
   * @param httpSender          the actor for sending requests
   * @param downloadUriResponse the response from the download URI request
   * @param system              the actor system
   * @return a future with the result of the download request
   */
  private def sendDownloadRequest(httpSender: ActorRef[HttpRequestSender.HttpCommand],
                                  downloadUriResponse: HttpRequestSender.SuccessResult)
                                 (implicit system: ActorSystem[_]):
  Future[HttpRequestSender.SuccessResult] = {
    val location = downloadUriResponse.response.header[Location]
    location match {
      case Some(downloadUri) =>
        val request = HttpRequest(uri = downloadUri.uri)
        executeRequest(httpSender, request, discardMode = DiscardEntityMode.OnFailure)
      case None =>
        Future.failed(
          new IllegalStateException(s"Request for download URI to ${downloadUriResponse.request.request.uri} " +
            "does not contain a Location header."))
    }
  }

  /**
   * Prepares a JSON request entity that is serialized from the given object.
   * This is a thin wrapper around Akka HTTP's JSON marshalling facilities.
   *
   * @param entity the entity of the request as object
   * @param m      a marshaller for this object
   * @param system the actor system
   * @tparam A the type of the entity
   * @return a future with the entity
   */
  private def prepareJsonRequestEntity[A](entity: A)(implicit m: Marshaller[A, RequestEntity], system: ActorSystem[_]):
  Future[RequestEntity] =
    Marshal(entity).to[RequestEntity]

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
      result <- executeRequest(httpSender, request, DiscardEntityMode.OnFailure)
      obj <- Unmarshal(result.response).to[R]
    } yield obj

  /**
   * Convenience function to execute an HTTP request with the given parameters.
   *
   * @param httpSender  the request sender actor
   * @param request     the request to be sent
   * @param discardMode the mode to deal with the entity
   * @param system      the actor system
   * @return a future with the result of the execution
   */
  private def executeRequest(httpSender: ActorRef[HttpRequestSender.HttpCommand], request: HttpRequest,
                             discardMode: DiscardEntityMode = DiscardEntityMode.Always)
                            (implicit system: ActorSystem[_]): Future[HttpRequestSender.SuccessResult] =
    HttpRequestSender.sendRequestSuccess(httpSender, request, null, discardMode)

  /**
   * Generates the URI to resolve an item with the given ID.
   *
   * @param id the ID of the desired item
   * @return the URI pointing to this item
   */
  private def itemUri(id: String): String = s"$baseUri/items/$id"

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
