/*
 * Copyright 2020-2024 The Developers Team.
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

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import com.github.cloudfiles.core.http.ProxySupport.{ProxySelectorFunc, SystemProxy}
import com.github.cloudfiles.core.http.auth.{AuthExtension, OAuthConfig, OAuthExtension}
import com.github.cloudfiles.core.http.{HttpRequestSender, MultiHostExtension, UriEncodingHelper}
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.marshalling.{Marshal, Marshaller}
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{Accept, Location}
import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}

import scala.concurrent.Future
import scala.reflect.ClassTag

object OneDriveFileSystem {
  /** The prefix to select the root path of a drive. */
  private val RootPrefix = "/root"

  /** The suffix to select the children of a drive item. */
  private val ChildrenSuffix = "/children"

  /** The suffix to request an upload session for a file. */
  private val UploadSessionSuffix = "/createUploadSession"

  /** The suffix to address the content of a file. */
  private val ContentSuffix = "/content"

  /** A query parameter to select only the ID field of a drive item. */
  private val SelectIDParam = "select=id"

  /** The property to generate marker objects in JSON. */
  private val Marker = OneDriveJsonProtocol.MarkerProperty()

  /** The header for accepting JSON responses. */
  private val AcceptJsonHeader = Accept(MediaRange(MediaTypes.`application/json`))

  /** A sequence with the standard headers to send for typical requests. */
  private val StdHeaders = List(AcceptJsonHeader)

  /**
   * Returns an actor behavior for sending HTTP requests that fulfills all the
   * requirements of the OneDrive file system implementation. This actor has
   * the following properties:
   *  - It supports multiple hosts.
   *  - Requests to the OneDrive API server are authorized with an OAuth token
   *    as defined by the ''OAuthConfig'' provided.
   *  - Requests to other hosts do not use authentication. (This refers to the
   *    hosts for uploading and downloading files; requests to these hosts
   *    should not have an ''Authorization'' header.)
   *
   * @param config           the OneDrive configuration
   * @param authConfig       the configuration of the OAuth provider
   * @param requestQueueSize the size of the request queue
   * @param proxy            the function to select the proxy
   * @return the behavior of an actor for sending HTTP requests on behalf of a
   *         OneDrive file system
   */
  def createHttpSender(config: OneDriveConfig, authConfig: OAuthConfig,
                       requestQueueSize: Int = HttpRequestSender.DefaultQueueSize,
                       proxy: ProxySelectorFunc = SystemProxy): Behavior[HttpRequestSender.HttpCommand] = {
    val serverUri = Uri(config.serverUri)
    val factory: MultiHostExtension.RequestActorFactory = (uri, queueSize, proxy) => {
      val creator: MultiHostExtension.RequestActorCreator = context => {
        def createSender(uri: Uri, requestQueueSize: Int = HttpRequestSender.DefaultQueueSize):
        ActorRef[HttpRequestSender.HttpCommand] =
          context.spawnAnonymous(HttpRequestSender(uri, requestQueueSize, proxy))

        if (uri.authority == serverUri.authority) {
          val idpSender = createSender(authConfig.tokenEndpoint)
          val sender = createSender(uri, queueSize)
          context.spawnAnonymous(OAuthExtension(sender, idpSender, authConfig))
        } else {
          createSender(uri, queueSize)
        }
      }
      Future.successful(creator)
    }

    MultiHostExtension(requestActorFactory = factory, requestQueueSize = requestQueueSize, proxy = proxy)
  }

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
      case folder: Model.Folder[String] => OneDriveModel.newFolder(id = folder.id, name = folder.name,
        description = folder.description).item
      case file: Model.File[String] => OneDriveModel.newFile(id = file.id, size = file.size, name = file.name,
        description = file.description).item
    }

  /**
   * Returns a ''DriveItem'' that corresponds to the item of the source element
   * provided with the given patch specification applied.
   *
   * @param source the source element
   * @param spec   the patch specification to apply
   * @return the patched ''DriveItem''
   */
  private def patchedItem(source: Model.Element[String], spec: ElementPatchSpec): DriveItem = {
    val item = itemFor(source)
    item.copy(name = spec.patchName getOrElse item.name,
      size = spec.patchSize getOrElse item.size)
  }

  /**
   * Convenience function to create a request that expects a JSON response. The
   * request uses standard headers.
   *
   * @param uri    the URI of the request
   * @param method the HTTP method
   * @param entity the entity
   * @return the configured ''HttpRequest''
   */
  private def jsonRequest(uri: Uri, method: HttpMethod = HttpMethods.GET,
                          entity: RequestEntity = HttpEntity.Empty): HttpRequest =
    HttpRequest(uri = uri, method = method, headers = StdHeaders, entity = entity)
}

/**
 * The OneDrive-specific implementation of the
 * [[com.github.cloudfiles.core.FileSystem]] trait.
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
 * HTTP requests must support this. The companion object of this class provides
 * a function that can create an actor that fulfills all these requirements.
 *
 * @param config the configuration
 */
class OneDriveFileSystem(val config: OneDriveConfig)
  extends ExtensibleFileSystem[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder,
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
        .copy(fileSystemInfo = None) // A FileSystemInfo is obviously not allowed for new folders.
      for {
        entity <- prepareJsonRequestEntity(writableItem)
        request = jsonRequest(method = HttpMethods.POST, uri = createUrl, entity = entity)
        response <- executeJsonRequest[DriveItem](httpSender, request)
      } yield response.id
  }

  override def updateFolder(folder: Model.Folder[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(folder)

  override def deleteFolder(folderID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteItem(folderID)

  override def createFile(parent: String, file: Model.File[String], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[String] = {
    if (file.size <= 0) {
      val uploadUri = s"${itemUri(parent)}:/${UriEncodingHelper.encode(file.name)}:"
      for {
        id <- emptyUpload(uploadUri)
        _ <- updateElement(file, Some(id))
      } yield id
    } else
      uploadFileWithProperties(s"${itemUri(parent)}:/${UriEncodingHelper.encode(file.name)}:$UploadSessionSuffix",
        file, content)
  }

  override def updateFile(file: Model.File[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(file)

  override def updateFileContent(fileID: String, size: Long, content: Source[ByteString, Any])
                                (implicit system: ActorSystem[_]): Operation[Unit] = {
    val uploadOp = if (size > 0)
      Operation { httpSender =>
        val uri = s"${itemUri(fileID)}$UploadSessionSuffix"
        val uploadSessionRequest = jsonRequest(uri = uri, method = HttpMethods.POST)
        uploadFile(uploadSessionRequest, size, content, httpSender)
      } else emptyUpload(itemUri(fileID))
    uploadOp map (_ => ())
  }

  override def updateFileAndContent(file: Model.File[String], content: Source[ByteString, Any])
                                   (implicit system: ActorSystem[_]): Operation[Unit] = {
    if (file.size > 0)
      uploadFileWithProperties(s"${itemUri(file.id)}$UploadSessionSuffix", file, content) map (_ => ())
    else for {
      _ <- emptyUpload(itemUri(file.id))
      _ <- updateElement(file)
    } yield ()
  }

  override def downloadFile(fileID: String)(implicit system: ActorSystem[_]): Operation[HttpEntity] = Operation {
    httpSender =>
      val downloadUriRequest = HttpRequest(uri = itemUri(fileID) + ContentSuffix)
      for {
        uriResult <- executeRequest(httpSender, downloadUriRequest)
        downloadResult <- sendDownloadRequest(httpSender, uriResult)
      } yield downloadResult.response.entity
  }

  override def deleteFile(fileID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteItem(fileID)

  override def patchFolder(source: Model.Folder[String], spec: ElementPatchSpec): OneDriveModel.OneDriveFolder =
    OneDriveModel.OneDriveFolder(patchedItem(source, spec))

  override def patchFile(source: Model.File[String], spec: ElementPatchSpec): OneDriveModel.OneDriveFile =
    OneDriveModel.OneDriveFile(patchedItem(source, spec))

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
      val rootRequest = jsonRequest(uri = uri)
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
      val folderRequest = jsonRequest(uri = itemUri(id))
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
    val contentRequest = jsonRequest(uri = uri)
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
   * @param optID   an optional ID overriding the one of the element
   * @param system  the actor system
   * @return the operation to update this element
   */
  private def updateElement(element: Model.Element[String], optID: Option[String] = None)
                           (implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      val writableItem = toWritableItem(itemFor(element))
      for {
        entity <- prepareJsonRequestEntity(writableItem)
        request = jsonRequest(method = HttpMethods.PATCH, uri = itemUri(optID getOrElse element.id), entity = entity)
        _ <- executeJsonRequest[DriveItem](httpSender, request)
      } yield ()
  }

  /**
   * Returns an operation that executes an empty upload to the given URI. This
   * is needed for files of size 0, which cannot be uploaded via the standard
   * mechanism.
   *
   * @param uploadPath the path where to upload the file
   * @param system     the actor system
   * @return the operation to do the empty upload
   */
  private def emptyUpload(uploadPath: String)(implicit system: ActorSystem[_]): Operation[String] =
    Operation { httpSender =>
      val entity = HttpEntity(ContentTypes.`application/octet-stream`, 0, Source.empty)
      val uploadRequest = HttpRequest(method = HttpMethods.PUT, uri = uploadPath + ContentSuffix,
        headers = StdHeaders, entity = entity)
      executeJsonRequest[DriveItem](httpSender, uploadRequest) map (_.id)
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
        val request = HttpRequest(uri = downloadUri.uri, headers = List(AuthExtension.EmptyAuthHeader))
        executeRequest(httpSender, request, discardMode = DiscardEntityMode.OnFailure)
      case None =>
        Future.failed(
          new IllegalStateException(s"Request for download URI to ${downloadUriResponse.request.request.uri} " +
            "does not contain a Location header."))
    }
  }

  /**
   * Returns an ''Operation'' to upload a file via an upload session and
   * setting properties for the file. In contrast to a plain upload operation,
   * the request for the upload session has a body with the properties to
   * update for the file.
   *
   * @param uploadUri the URI to request the upload session
   * @param file      the file to upload
   * @param content   the content of the file to upload
   * @param system    the actor system
   * @return the ''Operation'' to upload the file returning the file ID
   */
  private def uploadFileWithProperties(uploadUri: String, file: Model.File[String], content: Source[ByteString, Any])
                                      (implicit system: ActorSystem[_]): Operation[String] = Operation {
    httpSender =>
      val item = toWritableItem(itemFor(file))
      for {
        entity <- prepareJsonRequestEntity(UploadSessionRequest(item))
        uploadSessionRequest = jsonRequest(method = HttpMethods.POST, uri = uploadUri, entity = entity)
        result <- uploadFile(uploadSessionRequest, file.size, content, httpSender)
      } yield result
  }

  /**
   * Uploads a file by creating an upload session and then sending the content
   * of the file to the target upload URL.
   *
   * @param uploadSessionRequest the request for the upload session
   * @param size                 the file size
   * @param content              the source with the file's content
   * @param httpSender           the actor for sending requests
   * @param system               the actor system
   * @return a future with the ID of the uploaded file
   */
  private def uploadFile(uploadSessionRequest: HttpRequest, size: Long, content: Source[ByteString, Any],
                         httpSender: ActorRef[HttpRequestSender.HttpCommand])
                        (implicit system: ActorSystem[_]): Future[String] =
    for {
      sessionResponse <- executeJsonRequest[UploadSessionResponse](httpSender, uploadSessionRequest)
      result <- OneDriveUpload.upload(config, size, content, sessionResponse.uploadUrl, httpSender)
    } yield result

  /**
   * Prepares a JSON request entity that is serialized from the given object.
   * This is a thin wrapper around Pekko HTTP's JSON marshalling facilities.
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
    HttpRequestSender.sendRequestSuccess(httpSender, request, discardMode)

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
