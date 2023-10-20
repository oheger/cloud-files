/*
 * Copyright 2020-2023 The Developers Team.
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
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}

import scala.concurrent.Future

object GoogleDriveFileSystem {
  /** The endpoint for accessing the /files resource. */
  private val FilesEndpoint = "/drive/v3/files"

  /** The endpoint for file upload operations. */
  private val UploadEndpoint = s"/upload$FilesEndpoint"

  /**
   * Constant for the names of the fields that are requested for files.
   */
  private val FileFields = "id,name,size,createdTime,modifiedTime,mimeType,parents,properties,appProperties," +
    "md5Checksum,description,trashed,trashedTime"

  /**
   * Constant for the names of the fields that are requested when querying the
   * content of a folder.
   */
  private val FolderFields = s"nextPageToken,files($FileFields)"

  /**
   * Constant for the names of the fields that are requested when resolving
   * path components.
   */
  private val ResolveFields = "nextPageToken,files(id)"

  /** The header for accepting JSON responses. */
  private val AcceptJsonHeader = Accept(MediaRange(MediaTypes.`application/json`))

  /** A sequence with the standard headers to send for typical requests. */
  private val StdHeaders = List(AcceptJsonHeader)

  /**
   * Constant for the maximum page size supported by the Google Drive API.
   * This page size is set by requests to minimize the number of interactions
   * with the server necessary to retrieve all results.
   */
  private val MaxPageSize = "1000"

  /** The query parameter to selecting the fields to retrieve. */
  private val QueryParamFields = "fields"

  /**
   * The query parameter to adapt the page size of a folder content response.
   */
  private val QueryParamPageSize = "pageSize"

  /** The query parameter to set filter criteria for a folder content query. */
  private val QueryParamFilter = "q"

  /**
   * The query parameter containing the token that points to the next page of
   * the content listing of a folder.
   */
  private val QueryParamNextPage = "pageToken"

  /** The query parameter that requests the download of a file's content. */
  private val QueryParameterAlt = "alt"

  /** The query parameter that determines the upload type. */
  private val QueryParameterUploadType = "uploadType"

  /**
   * The map with query parameters to be used when resolving a file. Here a
   * parameter needs to be passed to select the required properties.
   */
  private val QueryParamsFileFields = Map(QueryParamFields -> FileFields)

  /**
   * The map with basic query parameters to be passed when requesting the
   * content of a folder. Some more parameters have to be added for an
   * individual request.
   */
  private val QueryParamsFolderContent = Map(QueryParamFields -> FolderFields, QueryParamPageSize -> MaxPageSize)

  /** The special mime type used to identify a folder in GoogleDrive. */
  private val MimeTypeFolder = "application/vnd.google-apps.folder"

  /** Constant for the reserved name of the root folder. */
  private val RootFolder = "root"

  /**
   * Constant for the value of the ''alt'' query parameter to request the
   * content of a file.
   */
  private val AltMedia = "media"

  /** Constant for the value of the upload type "resumable". */
  private val UploadResumable = "resumable"

  /**
   * Returns a map with all query parameters required to request the content of
   * the given folder.
   *
   * @param folderID       the ID of the folder affected
   * @param optPageToken   the optional token pointing to the next page
   * @param includeTrashed flag whether files in trash should be included
   * @return a map with the query parameters for this request
   */
  private def folderContentQueryParams(folderID: String, optPageToken: Option[String],
                                       includeTrashed: Boolean): Map[String, String] =
    optPageToken.fold(QueryParamsFolderContent) { token =>
      QueryParamsFolderContent + (QueryParamNextPage -> token)
    } + (QueryParamFilter -> trashFilter(s"'$folderID' in parents", includeTrashed))

  /**
   * Optionally adds a filter criterion to a query string depending on the
   * flag whether trashed files should be included.
   *
   * @param query          the original query string
   * @param includeTrashed the flag whether to include trashed files
   * @return the resulting query string
   */
  private def trashFilter(query: String, includeTrashed: Boolean): String =
    if (includeTrashed) query
    else s"$query and trashed = false"

  /**
   * Creates a model object (file or folder) for the given Google File. The
   * mime type of this file is evaluated to determine the correct type.
   *
   * @param googleFile the Google file
   * @return the model element associated with this file
   */
  private def createModelElement(googleFile: GoogleDriveJsonProtocol.File): GoogleDriveModel.GoogleDriveElement =
    googleFile.mimeType match {
      case MimeTypeFolder => GoogleDriveModel.GoogleDriveFolder(googleFile)
      case _ => GoogleDriveModel.GoogleDriveFile(googleFile)
    }

  /**
   * Creates a ''WritableFile'' from the optional properties provided. Part of
   * the properties are initialized from a source element. If this is an object
   * from the GoogleDrive model, extended properties are taken into account.
   *
   * @param srcElement         the source element
   * @param optMimeType        optional mime type
   * @param optParents         optional parents
   * @param mimeTypeFromSource if this parameter is '''true''', no mime type is
   *                           provided, and the source element is a Google
   *                           file, the mime type is obtained from this file
   * @return the ''WritableFile''
   */
  private def createWritableFile(srcElement: Model.Element[String], optMimeType: Option[String] = None,
                                 optParents: Option[List[String]] = None, mimeTypeFromSource: Boolean = false):
  GoogleDriveJsonProtocol.WritableFile = {
    val (properties, appProperties, srcMimeType) = srcElement match {
      case googleElem: GoogleDriveModel.GoogleDriveElement =>
        (googleElem.googleFile.properties, googleElem.googleFile.appProperties,
          if (mimeTypeFromSource) Option(googleElem.googleFile.mimeType) else None)
      case _ => (None, None, None)
    }
    GoogleDriveJsonProtocol.WritableFile(name = Option(srcElement.name), properties = properties,
      appProperties = appProperties, mimeType = srcMimeType orElse optMimeType, parents = optParents,
      createdTime = Option(srcElement.createdAt), modifiedTime = Option(srcElement.lastModifiedAt),
      description = srcElement.description, trashed = None, trashedTime = None)
  }

  /**
   * Applies an ''ElementPatchSpec'' to the given element. This function
   * obtains a [[GoogleDriveJsonProtocol.File]] object for the source element
   * (if the element is not a Google Drive element, a new one is created).
   * Then the patch spec is applied on this file. Finally, the provided
   * creation function is called to create the result.
   *
   * @param source  the source element to be patched
   * @param size    the optional file size
   * @param spec    the patch specification
   * @param fCreate the function to create the result element
   * @tparam T the type of the result element
   * @return the patched result element
   */
  private def patchElement[T](source: Model.Element[String], size: Option[Long], spec: ElementPatchSpec)
                             (fCreate: GoogleDriveJsonProtocol.File => T): T = {
    val googleFile = source match {
      case elem: GoogleDriveModel.GoogleDriveElement => elem.googleFile
      case _ =>
        GoogleDriveJsonProtocol.File(id = source.id, name = source.name, parents = null, mimeType = null,
          createdTime = source.createdAt, modifiedTime = source.lastModifiedAt, description = source.description,
          size = size map (_.toString), properties = None, appProperties = None)
    }
    val patchedFile = googleFile.copy(name = spec.patchName getOrElse googleFile.name,
      size = if (spec.patchSize.isDefined) spec.patchSize map (_.toString) else googleFile.size)
    fCreate(patchedFile)
  }

  /**
   * Generates the query parameter for resolving a single path component. The
   * query selects files in the parent folder with the given component name.
   * Unless the current component is the last one, only folders are selected.
   *
   * @param parent         the ID of the parent component
   * @param component      the name of the current component to resolve
   * @param tailComponents a sequence with remaining components
   * @param includeTrashed flag whether to include trashed files
   * @return the query string for this resolve operation
   */
  private def resolveQuery(parent: String, component: String, tailComponents: Seq[String], includeTrashed: Boolean):
  String = {
    val query = s"'$parent' in parents and name = '$component'"
    val folderQuery = if (tailComponents.isEmpty) query
    else s"$query and mimeType = '$MimeTypeFolder'"
    trashFilter(folderQuery, includeTrashed)
  }
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

  /**
   * Stores a list of path components for the optional root path. This is used
   * when resolving relative paths.
   */
  private val rootPathComponents =
    (config.optRootPath map UriEncodingHelper.splitAndDecodeComponents getOrElse Nil).toList

  /** The absolute URI prefix for accessing the files resource. */
  private val fileResourcePrefix = resolveEndpoint(FilesEndpoint)

  /** The absolute URI prefix for executing upload operations. */
  private val uploadResourcePrefix = resolveEndpoint(UploadEndpoint)

  override def patchFolder(source: Model.Folder[String], spec: ElementPatchSpec): GoogleDriveModel.GoogleDriveFolder =
    patchElement(source, None, spec)(GoogleDriveModel.GoogleDriveFolder)

  override def patchFile(source: Model.File[String], spec: ElementPatchSpec): GoogleDriveModel.GoogleDriveFile =
    patchElement(source, Some(source.size), spec)(GoogleDriveModel.GoogleDriveFile)

  /**
   * Resolves the ID of an element (file or folder) that is specified by its
   * path, allowing control whether to include trashed files. This is
   * analogous to the overloaded function, but instead of using the
   * ''includeTrashed'' flag from the configuration, the flag can be specified
   * explicitly.
   *
   * @param path           the path to resolve
   * @param includeTrashed the flag whether to include trashed files
   * @param system         the actor system
   * @return the ''Operation'' to resolve the path
   */
  def resolvePath(path: String, includeTrashed: Boolean)(implicit system: ActorSystem[_]): Operation[String] = {
    val components = if (path.isEmpty) Seq.empty
    else UriEncodingHelper.splitAndDecodeComponents(path)
    resolvePathComponents(components, includeTrashed)
  }

  override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[String] =
    resolvePath(path, config.includeTrashed)

  def resolvePathComponents(components: Seq[String], includeTrashed: Boolean)(implicit system: ActorSystem[_]):
  Operation[String] = Operation {
    httpSender =>
      val fullComponents = config.optRootPath.fold(components.toList) { _ =>
        rootPathComponents ::: components.toList
      }
      resolvePathComponent(httpSender, RootFolder, fullComponents, includeTrashed)
  }

  override def resolvePathComponents(components: Seq[String])(implicit system: ActorSystem[_]): Operation[String] =
    resolvePathComponents(components, config.includeTrashed)

  override def rootID(implicit system: ActorSystem[_]): Operation[String] = config.optRootPath match {
    case Some(_) => resolvePathComponents(Seq.empty)
    case None => Operation { _ => Future.successful(RootFolder) }
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

  /**
   * Returns an object describing the content of the folder with the given ID,
   * allowing control whether trashed files should be included. This is
   * analogous to the overloaded function, but instead of using the
   * ''includeTrashed'' flag from the configuration, the flag can be specified
   * explicitly.
   *
   * @param id             the ID of the folder
   * @param includeTrashed flag whether to include trashed files
   * @param system         the actor system
   * @return the ''Operation'' returning the content of this folder
   */
  def folderContent(id: String, includeTrashed: Boolean)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder]] =
    Operation { httpSender =>

      def processPage(files: List[GoogleDriveJsonProtocol.File], nextToken: Option[String]):
      Future[List[GoogleDriveJsonProtocol.File]] = {
        val uri = Uri(fileResourcePrefix).withQuery(Uri.Query(folderContentQueryParams(id, nextToken, includeTrashed)))
        val request = HttpRequest(uri = uri, headers = StdHeaders)
        executeQuery[GoogleDriveJsonProtocol.FolderResponse](httpSender, request) flatMap { response =>
          val nextFiles = files ++ response.files
          response.nextPageToken match {
            case None => Future.successful(nextFiles)
            case tok@Some(_) => processPage(nextFiles, tok)
          }
        }
      }

      processPage(Nil, None) map { googleFiles =>
        val (files, folders) = googleFiles.foldRight((Map.empty[String, GoogleDriveModel.GoogleDriveFile],
          Map.empty[String, GoogleDriveModel.GoogleDriveFolder])) { (file, maps) =>
          createModelElement(file) match {
            case modelFolder: GoogleDriveModel.GoogleDriveFolder =>
              (maps._1, maps._2 + (file.id -> modelFolder))
            case modelFile: GoogleDriveModel.GoogleDriveFile =>
              (maps._1 + (file.id -> modelFile), maps._2)
          }
        }
        Model.FolderContent(id, files, folders)
      }
    }

  override def folderContent(id: String)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder]] =
    folderContent(id, config.includeTrashed)

  override def createFolder(parent: String, folder: Model.Folder[String])(implicit system: ActorSystem[_]):
  Operation[String] = Operation {
    httpSender =>
      val requestFile = createWritableFile(folder, optMimeType = Some(MimeTypeFolder),
        optParents = Some(List(parent)))
      for {
        entity <- fileEntity(requestFile)
        request = HttpRequest(method = HttpMethods.POST, uri = Uri(fileResourcePrefix), entity = entity)
        response <- executeQuery[GoogleDriveJsonProtocol.FileReference](httpSender, request)
      } yield response.id
  }

  override def updateFolder(folder: Model.Folder[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(folder.id, createWritableFile(folder))

  override def deleteFolder(folderID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElement(folderID)

  override def createFile(parent: String, file: Model.File[String], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[String] = Operation {
    httpSender =>
      val uploadTriggerUri = Uri(uploadResourcePrefix)
        .withQuery(Uri.Query(QueryParameterUploadType -> UploadResumable))
      val requestFile = createWritableFile(file, optParents = Some(List(parent)), mimeTypeFromSource = true)

      for {
        entity <- fileEntity(requestFile)
        request = HttpRequest(method = HttpMethods.POST, uri = uploadTriggerUri, entity = entity)
        result <- sendUploadTriggerRequest(httpSender, request)
        uploadRequest <- createUploadRequest(result, file.size, content)
        uploadResult <- executeQuery[GoogleDriveJsonProtocol.FileReference](httpSender, uploadRequest)
      } yield uploadResult.id
  }

  override def updateFile(file: Model.File[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(file.id, createWritableFile(file))

  override def updateFileContent(fileID: String, size: Long, content: Source[ByteString, Any])
                                (implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      val uploadTriggerUri = Uri(s"$uploadResourcePrefix/$fileID")
        .withQuery(Uri.Query(QueryParameterUploadType -> UploadResumable))
      val triggerRequest = HttpRequest(method = HttpMethods.PATCH, uri = uploadTriggerUri)

      overwriteFileContent(httpSender, triggerRequest, size, content)
  }

  override def updateFileAndContent(file: Model.File[String], content: Source[ByteString, Any])
                                   (implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      val uploadTriggerUri = Uri(s"$uploadResourcePrefix/${file.id}")
        .withQuery(Uri.Query(QueryParameterUploadType -> UploadResumable))
      val requestFile = createWritableFile(file, mimeTypeFromSource = true)

      for {
        entity <- fileEntity(requestFile)
        request = HttpRequest(method = HttpMethods.PATCH, uri = uploadTriggerUri, entity = entity)
        uploadResult <- overwriteFileContent(httpSender, request, file.size, content)
      } yield uploadResult
  }

  override def downloadFile(fileID: String)(implicit system: ActorSystem[_]): Operation[HttpEntity] = Operation {
    httpSender =>
      val downloadUri = Uri(s"$fileResourcePrefix/$fileID").withQuery(Uri.Query(QueryParameterAlt -> AltMedia))
      val request = HttpRequest(uri = downloadUri)
      HttpRequestSender.sendRequestSuccess(httpSender, request, requestData = null) map { result =>
        result.response.entity
      }
  }

  override def deleteFile(fileID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElement(fileID)

  /**
   * Updates the metadata of an element (file or folder) according to the given
   * ''WritableFile'' object. This function can be easier to use than the
   * default update() functions offered by the ''FileSystem'' trait, as a
   * ''WritableFile'' object allows specifying the exact properties that should
   * be updated.
   *
   * @param id     the ID of the element to update
   * @param spec   a ''WritableFile'' with the properties to update
   * @param system the actor system
   * @return the ''Operation'' that updates the element
   */
  def updateElement(id: String, spec: GoogleDriveJsonProtocol.WritableFile)(implicit system: ActorSystem[_]):
  Operation[Unit] = Operation {
    httpSender =>
      for {
        entity <- fileEntity(spec)
        request = HttpRequest(method = HttpMethods.PATCH, uri = Uri(s"$fileResourcePrefix/$id"),
          entity = entity)
        response <- executeUpdate(httpSender, request)
      } yield response
  }


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
      val uri = Uri(s"$fileResourcePrefix/$id").withQuery(Uri.Query(QueryParamsFileFields))
      val request = HttpRequest(uri = uri, headers = StdHeaders)
      executeQuery[GoogleDriveJsonProtocol.File](httpSender, request) map createModelElement
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
      val deleteRequest = HttpRequest(method = HttpMethods.DELETE, uri = s"$fileResourcePrefix/$id")
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
      result <- HttpRequestSender.sendRequestSuccess(httpSender, request)
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
    HttpRequestSender.sendRequestSuccess(httpSender, request, discardMode).map(_ => ())

  /**
   * Produces a request entity from the given writable file. This is typically
   * needed for update operations.
   *
   * @param file   the file to convert
   * @param system the actor system
   * @return a ''Future'' with the request entity
   */
  private def fileEntity(file: GoogleDriveJsonProtocol.WritableFile)
                        (implicit system: ActorSystem[_]): Future[RequestEntity] =
    Marshal(file).to[RequestEntity]

  /**
   * Sends the request that triggers a file upload operation. This request
   * should be answered by the server with an empty response, but with a
   * ''Location'' header referring to the upload URI.
   *
   * @param httpSender the HTTP sender actor
   * @param request    the request to send
   * @param system     the actor system
   * @return the result of the request
   */
  private def sendUploadTriggerRequest(httpSender: ActorRef[HttpRequestSender.HttpCommand], request: HttpRequest)
                                      (implicit system: ActorSystem[_]): Future[HttpRequestSender.SuccessResult] =
    HttpRequestSender.sendRequestSuccess(httpSender, request, requestData = null,
      discardMode = DiscardEntityMode.Always)

  /**
   * Extracts the URI to upload a file from the ''Location'' header of the
   * response for the upload trigger request and creates a request for the
   * following file upload. Result is a ''Future'' to compose this function
   * with other operations; if no ''Location'' header can be extracted, the
   * ''Future'' fails.
   *
   * @param result   the result of the request that triggered the upload
   * @param fileSize the size of the file to be uploaded
   * @param content  a ''Source'' with the file content to upload
   * @return a ''Future'' with the file upload request
   */
  private def createUploadRequest(result: HttpRequestSender.SuccessResult, fileSize: Long,
                                  content: Source[ByteString, Any]): Future[HttpRequest] =
    result.response.header[Location] match {
      case Some(location) =>
        Future.successful(HttpRequest(uri = location.uri, method = HttpMethods.PUT,
          entity = HttpEntity(ContentTypes.`application/octet-stream`, fileSize, content)))
      case None =>
        Future.failed(new IllegalStateException(s"Could not extract 'Location' header from response: $result."))
    }

  /**
   * Executes the steps to overwrite the content of a file based on a request
   * to trigger the upload operation. The trigger request is sent, from its
   * response, the upload URI is extracted, and eventually the upload is done.
   *
   * @param httpSender     the HTTP sender actor
   * @param triggerRequest the request to trigger the upload
   * @param size           the file size
   * @param content        the content of the file
   * @param system         the actor system
   * @return a ''Future'' indicating success or failure
   */
  private def overwriteFileContent(httpSender: ActorRef[HttpRequestSender.HttpCommand], triggerRequest: HttpRequest,
                                   size: Long, content: Source[ByteString, Any])
                                  (implicit system: ActorSystem[_]): Future[Unit] = {
    for {
      result <- sendUploadTriggerRequest(httpSender, triggerRequest)
      uploadRequest <- createUploadRequest(result, size, content)
      uploadResult <- executeUpdate(httpSender, uploadRequest)
    } yield uploadResult
  }

  /**
   * Resolves a single component of a path. This function is called for each
   * component of a path to be resolved until the full path has been processed
   * or the remaining components cannot be resolved.
   *
   * @param httpSender     the actor for sending HTTP requests
   * @param parent         the current parent ID
   * @param components     the remaining components to resolve
   * @param includeTrashed flag whether to include trashed files
   * @param system         the actor system
   * @return a ''Future'' with the ID of the resolved path
   */
  private def resolvePathComponent(httpSender: ActorRef[HttpRequestSender.HttpCommand], parent: String,
                                   components: List[String], includeTrashed: Boolean)
                                  (implicit system: ActorSystem[_]): Future[String] =
    components match {
      case h :: t =>
        val queryParams = Map(QueryParamFields -> ResolveFields, QueryParamPageSize -> MaxPageSize,
          QueryParamFilter -> resolveQuery(parent, h, t, includeTrashed))
        resolvePathComponentQuery(httpSender, queryParams, Nil, None) flatMap { files =>
          resolvePathComponentAlternatives(httpSender, h, t, files, includeTrashed)
        }

      case _ => Future.successful(parent)
    }

  /**
   * Resolves a single component of a path by following all alternatives.
   * Google drive allows multiple files with the same names in a folder; all
   * of these may need to be tried in order to resolve a path.
   *
   * @param httpSender          the actor for sending HTTP requests
   * @param currentComponent    the name of the current path component
   * @param remainingComponents the remaining components to resolve
   * @param files               the files in the current folder
   * @param includeTrashed      flag whether to include trashed files
   * @param system              the actor system
   * @return a ''Future'' with the ID of the resolved path
   */
  private def resolvePathComponentAlternatives(httpSender: ActorRef[HttpRequestSender.HttpCommand],
                                               currentComponent: String,
                                               remainingComponents: List[String],
                                               files: List[GoogleDriveJsonProtocol.FileReference],
                                               includeTrashed: Boolean)
                                              (implicit system: ActorSystem[_]): Future[String] =
    files match {
      case h :: t =>
        resolvePathComponent(httpSender, h.id, remainingComponents, includeTrashed) recoverWith {
          case _ =>
            resolvePathComponentAlternatives(httpSender, currentComponent, remainingComponents, t, includeTrashed)
        }

      case Nil =>
        val unresolved = currentComponent :: remainingComponents
        val ex = new IllegalArgumentException(s"Could not resolve path components: '${unresolved.mkString("/")}'")
        Future.failed(ex)
    }

  /**
   * Queries all the files for a specific resolve step. This includes handling
   * of multiple result pages.
   *
   * @param httpSender   the actor for sending HTTP requests
   * @param queryParams  the query parameters for the current resolve step
   * @param files        the accumulated list of result files
   * @param optNextToken optional token for the next result page
   * @param system       the actor system
   * @return a ''Future'' with all the files matching the query parameters
   */
  private def resolvePathComponentQuery(httpSender: ActorRef[HttpRequestSender.HttpCommand],
                                        queryParams: Map[String, String],
                                        files: List[GoogleDriveJsonProtocol.FileReference],
                                        optNextToken: Option[String])(implicit system: ActorSystem[_]):
  Future[List[GoogleDriveJsonProtocol.FileReference]] = {
    val pagingQueryParams = optNextToken.fold(queryParams) { token =>
      queryParams + (QueryParamNextPage -> token)
    }
    val uri = Uri(fileResourcePrefix).withQuery(Uri.Query(pagingQueryParams))
    val request = HttpRequest(uri = uri, headers = StdHeaders)
    executeQuery[GoogleDriveJsonProtocol.ResolveResponse](httpSender, request) flatMap { response =>
      val currentFiles = response.files ++ files
      response.nextPageToken match {
        case optToken@Some(_) =>
          resolvePathComponentQuery(httpSender, queryParams, currentFiles, optToken)
        case None =>
          Future.successful(currentFiles)
      }
    }
  }

  /**
   * Generates an absolute URI that points to the specified endpoint on the
   * configured GoogleDrive server.
   *
   * @param endpoint the relative endpoint path
   * @return the absolute URI referencing this endpoint
   */
  private def resolveEndpoint(endpoint: String): String =
    s"${UriEncodingHelper.removeTrailingSeparator(config.serverUri)}$endpoint"

  /**
   * Defines the timeout for all operations by referring to the configuration.
   *
   * @return the timeout for requests
   */
  private implicit def requestTimeout: Timeout = config.timeout
}
