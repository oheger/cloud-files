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

package com.github.cloudfiles.webdav

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.{ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode

import scala.concurrent.Future
import scala.util.Try

object DavFileSystem {
  /** Media type of the data that is expected from the server. */
  private val MediaXML = MediaRange(MediaType.text("xml"))

  /** The Accept header to be used by all requests. */
  private val HeaderAccept = Accept(MediaXML)

  /** The Depth header to be used when querying the content of a folder. */
  private val HeaderDepthContent = DepthHeader("1")

  /** The Depth header to be used when resolving a specific element. */
  private val HeaderDepthElement = DepthHeader("0")

  /** Constant for the custom HTTP method used to query folders. */
  private val MethodPropFind = HttpMethod.custom("PROPFIND")

  /** Constant for the custom HTTP method used to create new folders. */
  private val MethodMkCol = HttpMethod.custom("MKCOL")

  /** Constant for the custom HTTP method to update element properties. */
  private val MethodPropPatch = HttpMethod.custom("PROPPATCH")

  /** An operation that does not execute any action. */
  private val NoOp: Operation[Unit] = Operation(_ => Future.successful(()))
}

/**
 * WebDav-specific implementation of the
 * [[com.github.cloudfiles.core.FileSystem]] trait.
 *
 * This class allows interacting with the files and folders stored on a WebDav
 * server, which is defined by the configuration provided. Elements on the
 * server are identified by their direct URIs; so relative paths can be
 * transformed to IDs in a straight-forward way.
 *
 * @param config the configuration
 */
class DavFileSystem(val config: DavConfig)
  extends ExtensibleFileSystem[Uri, DavModel.DavFile, DavModel.DavFolder,
    Model.FolderContent[Uri, DavModel.DavFile, DavModel.DavFolder]] {

  import DavFileSystem._

  /** The parser for DAV responses. */
  private val davParser = new DavParser(config.optDescriptionKey)

  /** The implicit timeout for HTTP requests. */
  private implicit val requestTimeout: Timeout = config.timeout

  override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[Uri] = Operation {
    _ =>
      val paramPath = Path(path)
      val relPath = if (paramPath.startsWithSlash) paramPath
      else Path.SingleSlash ++ paramPath
      val targetPath = config.rootUri.path ++ relPath
      Future.successful(config.rootUri.withPath(targetPath))
  }

  override def rootID(implicit system: ActorSystem[_]): Operation[Uri] = Operation {
    _ => Future.successful(config.rootUri)
  }

  override def resolveFile(id: Uri)(implicit system: ActorSystem[_]): Operation[DavModel.DavFile] = Operation {
    httpSender =>
      resolveElement(id, httpSender) flatMap {
        case file: DavModel.DavFile => Future.successful(file)
        case _ =>
          Future.failed(new IllegalArgumentException(s"URI $id could be resolved, but does not point to a file."))
      }
  }

  override def resolveFolder(id: Uri)(implicit system: ActorSystem[_]): Operation[DavModel.DavFolder] = Operation {
    httpSender =>
      resolveElement(withTrailingSlash(id), httpSender) flatMap {
        case folder: DavModel.DavFolder => Future.successful(folder)
        case _ =>
          Future.failed(new IllegalArgumentException(s"URI $id could be resolved, but does not point to a folder."))
      }
  }

  override def folderContent(id: Uri)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[Uri, DavModel.DavFile, DavModel.DavFolder]] = Operation {
    httpSender =>
      val contentRequest = HttpRequest(uri = withTrailingSlash(id), method = MethodPropFind,
        headers = List(HeaderAccept, HeaderDepthContent))
      for {
        response <- execute(httpSender, contentRequest)
        content <- davParser.parseFolderContent(response.response.entity.dataBytes)
      } yield content
  }

  override def createFolder(parent: Uri, folder: Model.Folder[Uri])(implicit system: ActorSystem[_]): Operation[Uri] =
    createDavFolder(parent, toDavFolder(folder))

  override def updateFolder(folder: Model.Folder[Uri])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateDavFolder(toDavFolder(folder))

  override def deleteFolder(folderID: Uri)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElementOp(withTrailingSlash(folderID))

  override def createFile(parent: Uri, file: Model.File[Uri], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[Uri] =
    createDavFile(parent, toDavFile(file), content)

  override def updateFile(file: Model.File[Uri])(implicit system: ActorSystem[_]): Operation[Unit] =
    updateDavFile(toDavFile(file))

  override def updateFileContent(fileID: Uri, size: Long, content: Source[ByteString, Any])
                                (implicit system: ActorSystem[_]): Operation[Unit] = {
    for {
      _ <- conditionallyDeleteFileOp(fileID)
      _ <- Operation {
        httpSender =>
          executeUploadRequest(httpSender, fileID, size, content) map (_ => ())
      }
    } yield ()
  }

  override def downloadFile(fileID: Uri)(implicit system: ActorSystem[_]): Operation[HttpEntity] = Operation {
    httpSender =>
      val downloadRequest = HttpRequest(uri = fileID)
      execute(httpSender, downloadRequest) map (_.response.entity)
  }

  override def deleteFile(fileID: Uri)(implicit system: ActorSystem[_]): Operation[Unit] =
    deleteElementOp(fileID)

  override def patchFolder(source: Model.Folder[Uri], spec: ElementPatchSpec): DavModel.DavFolder = {
    val davFolder = toDavFolder(source)
    davFolder.copy(name = spec.patchName getOrElse davFolder.name)
  }

  override def patchFile(source: Model.File[Uri], spec: ElementPatchSpec): DavModel.DavFile = {
    val davFile = toDavFile(source)
    davFile.copy(name = spec.patchName getOrElse davFile.name,
      size = spec.patchSize getOrElse davFile.size)
  }

  /**
   * Resolves an element by its ID and looks up its properties.
   *
   * @param uri        the URI of the element
   * @param httpSender the actor to send HTTP requests
   * @param system     the actor system
   * @return a ''Future'' with the resolved element
   */
  private def resolveElement(uri: Uri, httpSender: ActorRef[HttpRequestSender.HttpCommand])
                            (implicit system: ActorSystem[_]): Future[Model.Element[Uri]] = {
    val folderRequest = HttpRequest(uri = uri, method = MethodPropFind,
      headers = List(HeaderAccept, HeaderDepthElement))
    for {
      response <- execute(httpSender, folderRequest)
      elem <- davParser.parseElement(response.response.entity.dataBytes)
    } yield elem
  }

  /**
   * Returns an operation to delete the element with the URI provided.
   *
   * @param uri    the URI of the element to delete
   * @param system the actor system
   * @return the operation to delete this element
   */
  private def deleteElementOp(uri: Uri)(implicit system: ActorSystem[_]): Operation[Unit] = Operation {
    httpSender =>
      val delRequest = HttpRequest(method = HttpMethods.DELETE, uri = uri)
      executeAndDiscardEntity(httpSender, delRequest)
  }

  /**
   * Evaluates the ''deleteBeforeOverride'' flag from the configuration and
   * returns an ''Operation'' with either deletes the given file or does
   * nothing.
   *
   * @param uri    the URI of the file in question
   * @param system the actor system
   * @return the operation to conditionally delete the file
   */
  private def conditionallyDeleteFileOp(uri: Uri)(implicit system: ActorSystem[_]): Operation[Unit] =
    if (config.deleteBeforeOverride) deleteElementOp(uri)
    else NoOp

  /**
   * Internal implementation of the operation to create a new folder. This
   * function operates on a ''DavFolder'' and uses the attributes of this
   * folder to set additional properties if necessary.
   *
   * @param parent the URI of the parent folder
   * @param folder the object describing the new folder
   * @param system the actor system
   * @return the operation to create the new folder
   */
  private def createDavFolder(parent: Uri, folder: DavModel.DavFolder)(implicit system: ActorSystem[_]):
  Operation[Uri] = Operation {
    httpSender =>
      val newFolderUri = childElementUri(parent, folder.name)
      val createFolderRequest = HttpRequest(method = MethodMkCol, uri = newFolderUri)
      executeAndDiscardEntity(httpSender, createFolderRequest) flatMap { _ =>
        PropPatchGenerator.generatePropPatch(folder.attributes, folder.description, config.optDescriptionKey) match {
          case Some(patchBody) =>
            val patchRequest = HttpRequest(method = MethodPropPatch, uri = withTrailingSlash(newFolderUri),
              entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, patchBody))
            executeAndDiscardEntity(httpSender, patchRequest)
          case None =>
            Future.successful(())
        }
      } map (_ => newFolderUri)
  }

  /**
   * Internal implementation of the operation to update a folder. The folder
   * to update must have already been converted to a ''DavFolder''.
   *
   * @param folder the folder to update
   * @param system the actor system
   * @return the operation to update the folder
   */
  private def updateDavFolder(folder: DavModel.DavFolder)(implicit system: ActorSystem[_]): Operation[Unit] =
    Operation {
      httpSender =>
        executePatchRequest(httpSender, withTrailingSlash(folder.id), folder.description,
          folder.attributes)
    }

  /**
   * Converts a ''Folder'' object to a ''DavFolder''. If the folder is already
   * of the correct type, it is used as is. Otherwise, a new ''DavFolder'' is
   * created based on the properties of the folder provided.
   *
   * @param folder the source folder
   * @return the resulting ''DavFolder''
   */
  private def toDavFolder(folder: Model.Folder[Uri]): DavModel.DavFolder =
    folder match {
      case df: DavModel.DavFolder => df
      case _ =>
        DavModel.DavFolder(folder.id, folder.name, folder.description, folder.createdAt, folder.lastModifiedAt,
          DavModel.Attributes(Map.empty))
    }

  /**
   * Internal implementation of the operation to create a file. The object
   * describing the new file must have already been converted to a ''DavFile''.
   *
   * @param parent  the URI of the parent folder
   * @param file    the object describing the new file
   * @param content the source with the content of the file
   * @param system  the actor system
   * @return the operation to create the file
   */
  private def createDavFile(parent: Uri, file: DavModel.DavFile, content: Source[ByteString, Any])
                           (implicit system: ActorSystem[_]): Operation[Uri] = Operation {
    httpSender =>
      val newFileUri = childElementUri(parent, file.name)
      for {
        uri <- executeUploadRequest(httpSender, newFileUri, file.size, content)
        _ <- executePatchRequest(httpSender, newFileUri, file.description, file.attributes)
      } yield uri
  }

  /**
   * Internal implementation of the operation to update a file. The file to
   * update must have already been converted to a ''DavFile''.
   *
   * @param file   the file to update
   * @param system the actor system
   * @return the operation to update the file
   */
  private def updateDavFile(file: DavModel.DavFile)(implicit system: ActorSystem[_]): Operation[Unit] =
    Operation {
      httpSender => executePatchRequest(httpSender, file.id, file.description, file.attributes)
    }

  /**
   * Converts a ''File'' object to a ''DavFile''. If the file is already of the
   * correct type, it is used as is. Otherwise, a new ''DavFile'' is created
   * based on the properties of the file provided.
   *
   * @param file the original ''File''
   * @return the resulting ''DavFile''
   */
  private def toDavFile(file: Model.File[Uri]): DavModel.DavFile =
    file match {
      case f: DavModel.DavFile => f
      case _ =>
        DavModel.DavFile(file.id, file.name, file.description, file.createdAt, file.lastModifiedAt, file.size,
          DavModel.Attributes(Map.empty))
    }

  /**
   * Generates the URI of a child element based on the parent URI and its name.
   *
   * @param parent the parent URI
   * @param name   the name of the element
   * @return the URI of the child element
   */
  private def childElementUri(parent: Uri, name: String): Uri =
    parent.withPath(parent.path ?/ name)

  /**
   * Executes a request to patch the properties of the given element if
   * necessary.
   *
   * @param httpSender the request sender actor
   * @param uri        the URI of the element to patch
   * @param desc       the element description
   * @param attributes the attributes of the element
   * @param system     the actor system
   * @return a ''Future'' with the result of the operation
   */
  private def executePatchRequest(httpSender: ActorRef[HttpRequestSender.HttpCommand], uri: Uri, desc: Option[String],
                                  attributes: DavModel.Attributes)(implicit system: ActorSystem[_]): Future[Unit] =
    PropPatchGenerator.generatePropPatch(attributes, desc, config.optDescriptionKey)
      .map { xml =>
        val patchRequest = HttpRequest(method = MethodPropPatch, uri = uri,
          entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml))
        executeAndDiscardEntity(httpSender, patchRequest)
      } getOrElse Future.successful(())

  /**
   * Executes a request to upload the content of a file.
   *
   * @param httpSender the request sender actor
   * @param uri        the URI of the file in question
   * @param size       the content length
   * @param content    the source with the file content
   * @param system     the actor system
   * @return a ''Future'' with the URI of the target file
   */
  private def executeUploadRequest(httpSender: ActorRef[HttpRequestSender.HttpCommand], uri: Uri, size: Long,
                                   content: Source[ByteString, Any])(implicit system: ActorSystem[_]): Future[Uri] = {
    val entity = HttpEntity(ContentTypes.`application/octet-stream`, size, content)
    val uploadRequest = HttpRequest(method = HttpMethods.PUT, entity = entity, uri = uri)
    executeAndDiscardEntity(httpSender, uploadRequest) map (_ => uri)
  }

  /**
   * Executes an HTTP request against the given request sender actor with
   * error handling.
   *
   * @param httpSender  the HTTP request sender actor
   * @param request     the request to execute
   * @param discardMode controls when to discard the entity bytes
   * @param system      the actor system
   * @return a ''Future'' with the successful result
   */
  private def execute(httpSender: ActorRef[HttpRequestSender.HttpCommand], request: HttpRequest,
                      discardMode: DiscardEntityMode = DiscardEntityMode.OnFailure)
                     (implicit system: ActorSystem[_]): Future[HttpRequestSender.SuccessResult] =
    HttpRequestSender.sendRequestSuccess(httpSender, request, null, discardMode)

  /**
   * Executes an HTTP request against the given request sender actor, for which
   * the response entity does not matter. The content of the entity is directly
   * discarded.
   *
   * @param httpSender the HTTP request sender actor
   * @param request    the request to execute
   * @param system     the actor system
   * @return a ''Future'' indicating when the request has been executed
   */
  private def executeAndDiscardEntity(httpSender: ActorRef[HttpRequestSender.HttpCommand], request: HttpRequest)
                                     (implicit system: ActorSystem[_]): Future[Unit] =
    execute(httpSender, request, DiscardEntityMode.Always) map (_ => ())
}

/**
 * Class representing the ''Depth'' header.
 *
 * This header has to be included to WebDav requests. It defines the depth of
 * sub structures to be returned by a ''PROPFIND'' request.
 *
 * @param depth the value of the header
 */
class DepthHeader(depth: String) extends ModeledCustomHeader[DepthHeader] {
  override val companion: ModeledCustomHeaderCompanion[DepthHeader] = DepthHeader

  override def value(): String = depth

  override def renderInRequests(): Boolean = true

  override def renderInResponses(): Boolean = true
}

object DepthHeader extends ModeledCustomHeaderCompanion[DepthHeader] {
  override val name: String = "Depth"

  override def parse(value: String): Try[DepthHeader] =
    Try(new DepthHeader(value))
}
