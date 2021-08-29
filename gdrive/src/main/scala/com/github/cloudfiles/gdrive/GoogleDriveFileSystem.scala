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
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, Location, `Content-Type`}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import com.github.cloudfiles.core.{FileSystem, Model}

import scala.concurrent.Future

object GoogleDriveFileSystem {
  /** The URI prefix for accessing the /files resource. */
  private val FileResourcePrefix = "/drive/v3/files"

  /** The URI prefix for file upload operations. */
  private val UploadResourcePrefix = s"/upload$FileResourcePrefix"

  /**
   * Constant for the names of the fields that are requested for files.
   */
  private val FileFields = "id,name,size,createdTime,modifiedTime,mimeType,parents,properties,appProperties," +
    "md5Checksum,description"

  /**
   * Constant for the names of the fields that are requested when querying the
   * content of a folder.
   */
  private val FolderFields = s"nextPageToken,files($FileFields)"

  /** The header for accepting JSON responses. */
  private val AcceptJsonHeader = Accept(MediaRange(MediaTypes.`application/json`))

  /** The header indicating a JSON request */
  private val ContentJsonHeader = `Content-Type`(ContentTypes.`application/json`)

  /** A sequence with the standard headers to send for typical requests. */
  private val StdHeaders = List(AcceptJsonHeader)

  /** A sequence with standard headers to send for typical update requests. */
  private val StdUpdateHeaders = List(ContentJsonHeader)

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
  private val QueryParamsFolderContent = Map(QueryParamFields -> FolderFields, QueryParamPageSize -> "1000")

  /** The special mime type used to identify a folder in GoogleDrive. */
  private val MimeTypeFolder = "application/vnd.google-apps.folder"

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
   * @param folderID     the ID of the folder affected
   * @param optPageToken the optional token pointing to the next page
   * @return a map with the query parameters for this request
   */
  private def folderContentQueryParams(folderID: String, optPageToken: Option[String]): Map[String, String] =
    optPageToken.fold(QueryParamsFolderContent) { token =>
      QueryParamsFolderContent + (QueryParamNextPage -> token)
    } + (QueryParamFilter -> s"'$folderID' in parents and trashed = false")

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
      description = Option(srcElement.description))
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
          createdTime = source.createdAt, modifiedTime = source.lastModifiedAt, description = Option(source.description),
          size = size map (_.toString), properties = None, appProperties = None)
    }
    val patchedFile = googleFile.copy(name = spec.patchName getOrElse googleFile.name,
      description = if (spec.patchDescription.isDefined) spec.patchDescription else googleFile.description,
      size = if (spec.patchSize.isDefined) spec.patchSize map (_.toString) else googleFile.size)
    fCreate(patchedFile)
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

  override def patchFolder(source: Model.Folder[String], spec: ElementPatchSpec): GoogleDriveModel.GoogleDriveFolder =
    patchElement(source, None, spec)(GoogleDriveModel.GoogleDriveFolder)

  override def patchFile(source: Model.File[String], spec: ElementPatchSpec): GoogleDriveModel.GoogleDriveFile =
    patchElement(source, Some(source.size), spec)(GoogleDriveModel.GoogleDriveFile)

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

  override def folderContent(id: String)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder]] =
    Operation { httpSender =>

      def processPage(files: List[GoogleDriveJsonProtocol.File], nextToken: Option[String]):
      Future[List[GoogleDriveJsonProtocol.File]] = {
        val uri = Uri(FileResourcePrefix).withQuery(Uri.Query(folderContentQueryParams(id, nextToken)))
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

  override def createFolder(parent: String, folder: Model.Folder[String])(implicit system: ActorSystem[_]):
  Operation[String] = Operation {
    httpSender =>
      val requestFile = createWritableFile(folder, optMimeType = Some(MimeTypeFolder),
        optParents = Some(List(parent)))
      for {
        entity <- fileEntity(requestFile)
        request = HttpRequest(method = HttpMethods.POST, uri = Uri(FileResourcePrefix), headers = StdUpdateHeaders,
          entity = entity)
        response <- executeQuery[GoogleDriveJsonProtocol.File](httpSender, request)
      } yield response.id
  }

  override def updateFolder(folder: Model.Folder[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(folder)

  override def deleteFolder(folderID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElement(folderID)

  override def createFile(parent: String, file: Model.File[String], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[String] = Operation {
    httpSender =>
      val uploadTriggerUri = Uri(UploadResourcePrefix)
        .withQuery(Uri.Query(QueryParameterUploadType -> UploadResumable))
      val requestFile = createWritableFile(file, optParents = Some(List(parent)), mimeTypeFromSource = true)

      for {
        entity <- fileEntity(requestFile)
        request = HttpRequest(method = HttpMethods.POST, uri = uploadTriggerUri, headers = StdUpdateHeaders,
          entity = entity)
        result <- sendUploadTriggerRequest(httpSender, request)
        uploadRequest <- createUploadRequest(result, file.size, content)
        uploadResult <- executeQuery[GoogleDriveJsonProtocol.File](httpSender, uploadRequest)
      } yield uploadResult.id
  }

  override def updateFile(file: Model.File[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateElement(file)

  override def updateFileContent(fileID: String, size: Long, content: Source[ByteString, Any])
                                (implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      val uploadTriggerUri = Uri(s"$UploadResourcePrefix/$fileID")
        .withQuery(Uri.Query(QueryParameterUploadType -> UploadResumable))
      val triggerRequest = HttpRequest(method = HttpMethods.PATCH, uri = uploadTriggerUri)

      overwriteFileContent(httpSender, triggerRequest, size, content)
  }

  override def updateFileAndContent(file: Model.File[String], content: Source[ByteString, Any])
                                   (implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      val uploadTriggerUri = Uri(s"$UploadResourcePrefix/${file.id}")
        .withQuery(Uri.Query(QueryParameterUploadType -> UploadResumable))
      val requestFile = createWritableFile(file, mimeTypeFromSource = true)

      for {
        entity <- fileEntity(requestFile)
        request = HttpRequest(method = HttpMethods.PATCH, uri = uploadTriggerUri, headers = StdUpdateHeaders,
          entity = entity)
        uploadResult <- overwriteFileContent(httpSender, request, file.size, content)
      } yield uploadResult
  }

  override def downloadFile(fileID: String)(implicit system: ActorSystem[_]): Operation[HttpEntity] = Operation {
    httpSender =>
      val downloadUri = Uri(s"$FileResourcePrefix/$fileID").withQuery(Uri.Query(QueryParameterAlt -> AltMedia))
      val request = HttpRequest(uri = downloadUri)
      HttpRequestSender.sendRequestSuccess(httpSender, request, requestData = null) map { result =>
        result.response.entity
      }
  }

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
      val uri = Uri(s"$FileResourcePrefix/$id").withQuery(Uri.Query(QueryParamsFileFields))
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
      val deleteRequest = HttpRequest(method = HttpMethods.DELETE, uri = s"$FileResourcePrefix/$id")
      executeUpdate(httpSender, deleteRequest)
  }

  /**
   * Performs an update of a file or folder based on the given element. A PATCH
   * request is sent for the affected element that lists the properties to be
   * updated.
   *
   * @param element the element to be updated
   * @param system  the actor system
   * @return a ''Future'' with the outcome of the operation
   */
  private def updateElement(element: Model.Element[String])(implicit system: ActorSystem[_]): Operation[Unit] =
    Operation {
      httpSender =>
        val requestFile = createWritableFile(element)
        for {
          entity <- fileEntity(requestFile)
          request = HttpRequest(method = HttpMethods.PATCH, uri = Uri(s"$FileResourcePrefix/${element.id}"),
            headers = StdUpdateHeaders, entity = entity)
          response <- executeUpdate(httpSender, request)
        } yield response
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
   * Defines the timeout for all operations by referring to the configuration.
   *
   * @return the timeout for requests
   */
  private implicit def requestTimeout: Timeout = config.timeout
}
