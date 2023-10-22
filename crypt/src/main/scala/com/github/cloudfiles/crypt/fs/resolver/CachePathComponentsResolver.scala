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

package com.github.cloudfiles.crypt.fs.resolver

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.http.factory.Spawner
import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}
import com.github.cloudfiles.core.utils.LRUCache
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.crypt.fs.CryptConfig
import com.github.cloudfiles.crypt.service.CryptService
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.util.Timeout
import org.slf4j.Logger

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/**
 * A module providing a [[PathResolver]] implementation that caches the paths
 * it has already resolved.
 *
 * This resolver implementation is appropriate if there are many operations to
 * resolve paths and if the algorithm in used yields different results for each
 * encrypt operation. Internally, it makes use of an actor that manages an LRU
 * cache of a configured size. When asked to resolve a specific path, it
 * iterates over the single path components. The components that have already
 * been resolved can be looked up directly from the cache. For the remaining
 * components, the content of the single folders are loaded, and all element
 * names are decrypted and added to the cache. So, provided that the cache has
 * a sufficient size, later resolve operations for elements in the same folder
 * structure can be served efficiently.
 */
object CachePathComponentsResolver {
  /** The default timeout for resolve operations. */
  final val DefaultResolveTimeout = Timeout(30.seconds)

  /**
   * The base type of messages processed by the actor that manages the cache of
   * already resolved paths.
   *
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  sealed trait PathLookupCommand[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]

  /**
   * Internally used message class processed by the cache management actor that
   * requests a resolve operation of the given path components.
   *
   * @param pathComponents the path components to resolve
   * @param fileSystem     the file system
   * @param httpSender     the actor for sending requests
   * @param cryptConfig    the cryptographic configuration
   * @param client         the actor receiving results
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class PathLookupRequest[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](pathComponents: Seq[String],
                                fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                httpSender: ActorRef[HttpRequestSender.HttpCommand],
                                cryptConfig: CryptConfig,
                                client: ActorRef[PathLookupResult[ID]])
    extends PathLookupCommand[ID, FILE, FOLDER]

  /**
   * Internally used message class that reports a bunch of resolved file system
   * elements to the cache management actor. A message of this class is sent to
   * the actor when a folder has been loaded and a chunk of the element names
   * it contains has been decrypted.
   *
   * @param request    the original request to resolve the folder
   * @param elementIDs a map with decrypted names and their IDs
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class ResolveFolderResult[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](request: ResolveFolderRequest[ID, FILE, FOLDER],
                                elementIDs: Map[String, ID])
    extends PathLookupCommand[ID, FILE, FOLDER]

  /**
   * Internally used message class that is sent by the folder resolver actor
   * when all folder elements have been decrypted.
   *
   * @param request the original request to resolve the folder
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class ResolveFolderDone[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](request: ResolveFolderRequest[ID, FILE, FOLDER])
    extends PathLookupCommand[ID, FILE, FOLDER]

  /**
   * Internally used message class that reports an error when resolving a
   * folder to the cache management actor.
   *
   * @param request   the original request to resolve the folder
   * @param exception the exception causing this failure response
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class ResolveFolderError[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](request: ResolveFolderRequest[ID, FILE, FOLDER],
                                exception: Throwable)
    extends PathLookupCommand[ID, FILE, FOLDER]

  /**
   * Internally used message class that reports the outcome of the operation to
   * resolve the root folder ID of the file system associated with this
   * resolver.
   *
   * @param request     the original request to resolve the folder
   * @param triedRootID a ''Try'' with the outcome of the resolve root
   *                    operation
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class GotRootID[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](request: PathLookupRequest[ID, FILE, FOLDER], triedRootID: Try[ID])
    extends PathLookupCommand[ID, FILE, FOLDER]

  /**
   * Internally used message class that tells the path resolver actor to stop
   * itself. This message is sent to the actor from the ''close()'' function of
   * the resolver.
   *
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class StopActor[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]()
    extends PathLookupCommand[ID, FILE, FOLDER]

  /**
   * The base type for commands processed by the actor to decrypt the content
   * of a folder.
   *
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private sealed trait ResolveFolderCommand[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]

  /**
   * Internally used message class that triggers a resolve operation on a given
   * folder. This message is processed by the folder resolver actor. It loads
   * the folder referenced by this message and decrypts the names of all
   * elements it contains.[[ResolveFolderResult]] messages are sent whenever a
   * chunk of the given size has been processed.
   *
   * @param folderID    the ID of the folder in question
   * @param chunkSize   the chunk size for decrypt operations
   * @param pathRequest the ''PathLookupRequest'' that triggers this request
   * @param prefix      the prefix path of the folder
   * @param client      the actor receiving results
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class ResolveFolderRequest[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](folderID: ID,
                                chunkSize: Int,
                                pathRequest: PathLookupRequest[ID, FILE, FOLDER],
                                prefix: String,
                                client: ActorRef[PathLookupCommand[ID, FILE, FOLDER]])
    extends ResolveFolderCommand[ID, FILE, FOLDER]

  /**
   * Internally used message class that transports the content of a folder
   * which has been requested from a ''FileSystem''.
   *
   * @param request      the original request to resolve a folder
   * @param triedContent a ''Try'' with the content of the folder
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class GotFolderContent[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](request: ResolveFolderRequest[ID, FILE, FOLDER],
                                triedContent: Try[Model.FolderContent[ID, FILE, FOLDER]])
    extends ResolveFolderCommand[ID, FILE, FOLDER]

  /**
   * The base type for the results of a path lookup operation.
   *
   * @tparam ID the ID type
   */
  private sealed trait PathLookupResult[ID] {
    /**
     * Returns a ''Future'' with the result represented by this object. This
     * result is returned to the caller of the resolve operation.
     *
     * @return the ''Future'' with the result of the lookup operation
     */
    def resultFuture: Future[ID]
  }

  /**
   * A successful result of a path lookup operation.
   *
   * @param id the ID that has been resolved
   * @tparam ID the ID type
   */
  private case class PathLookupSuccessResult[ID](id: ID) extends PathLookupResult[ID] {
    override def resultFuture: Future[ID] = Future.successful(id)
  }

  /**
   * A failure result of a path lookup operation.
   *
   * @param exception the exception causing this failure response
   * @tparam ID the ID type
   */
  private case class PathLookupErrorResult[ID](exception: Throwable) extends PathLookupResult[ID] {
    override def resultFuture: Future[ID] = Future.failed(exception)
  }

  /**
   * A data class for storing information about a ''PathLookupRequest'' that is
   * currently in progress. The data allows to continue processing of this
   * request when the content of a folder becomes available.
   *
   * @param request             the original ''PathLookupRequest''
   * @param path                the full path to be resolved
   * @param resolvedID          the last known ID of a component
   * @param remainingComponents the remaining components to resolve
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class RequestProgress[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](request: PathLookupRequest[ID, FILE, FOLDER],
                                path: String,
                                resolvedID: ID,
                                remainingComponents: List[String]) {
    /**
     * Returns a flag whether this request has been completed.
     *
     * @return '''true''' if this request is complete; '''false''' otherwise
     */
    def isComplete: Boolean = remainingComponents.isEmpty
  }

  /**
   * A data class holding information about the current state of resolve
   * operations. Instances are used to handle incoming resolve requests or to
   * determine the next steps when folders have been resolved.
   *
   * @param nextCache      the updated cache
   * @param nextProgress   the updated map with requests in progress
   * @param folderRequests a list with new folder requests to trigger
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class ResolveStep[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](nextCache: LRUCache[String, ID],
                                nextProgress: Map[ID, List[RequestProgress[ID, FILE, FOLDER]]],
                                folderRequests: List[ResolveFolderRequest[ID, FILE, FOLDER]]) {
    /**
     * Updates this step by adding the given progress object. If the progress
     * is not yet complete, it must be added to the requests in progress; if
     * necessary, a folder request must be created, too.
     *
     * @param progress  the ''RequestProgress''
     * @param prefix    the path prefix for the current resolve operation
     * @param chunkSize the chunk size for decrypt operations
     * @param ctx       the context of the client actor
     * @return an instance updated with this ''RequestProgress''
     */
    def addProgress(progress: RequestProgress[ID, FILE, FOLDER], prefix: String, chunkSize: Int,
                    ctx: ActorContext[PathLookupCommand[ID, FILE, FOLDER]]): ResolveStep[ID, FILE, FOLDER] =
      if (progress.isComplete) this
      else {
        val requestsInProgress = nextProgress.getOrElse(progress.resolvedID, Nil)
        val nextFolderRequests = if (requestsInProgress.nonEmpty) folderRequests
        else ResolveFolderRequest(progress.resolvedID, chunkSize, progress.request, prefix, ctx.self) :: folderRequests
        copy(nextProgress = nextProgress + (progress.resolvedID -> (progress :: requestsInProgress)),
          folderRequests = nextFolderRequests)
      }
  }

  /**
   * Creates a new instance of ''CachePathComponentsResolver'' with the given
   * parameters. This function creates an actor to resolve paths and manage the
   * cache with already resolved paths via the spawner provided. It is also
   * possible to configure a chunk size for decrypt operations. If the chunk
   * size is negative (which is the default), the names of all elements in a
   * folder are decrypted at once, and then this result is processed further.
   * For chunk sizes greater than 0, results are already processed when this
   * number of names has been decrypted; this allows for some more parallelism,
   * and a request may be answered earlier when the required piece of
   * information becomes available.
   *
   * @param spawner          the ''Spawner'' to create the resolver actor
   * @param cacheSize        the size of the cache
   * @param optActorName     the optional actor name
   * @param decryptChunkSize the chunk size for decrypt operations
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return the new resolver instance
   */
  def apply[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]](spawner: Spawner, cacheSize: Int,
                                                                    decryptChunkSize: Int = -1,
                                                                    optActorName: Option[String] = None)
                                                                   (implicit timeout: Timeout):
  PathResolver[ID, FILE, FOLDER] =
    apply(spawner.spawn(pathResolverActor[ID, FILE, FOLDER](cacheSize, decryptChunkSize), optActorName))

  /**
   * Creates a new instance of ''CachePathComponentsResolver'' that uses the
   * given resolver actor to handle resolve requests.
   *
   * @param resolverActor the actor that does the resolving
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return the new resolver instance
   */
  def apply[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](resolverActor: ActorRef[PathLookupCommand[ID, FILE, FOLDER]])
                               (implicit timeout: Timeout):
  PathResolver[ID, FILE, FOLDER] = new PathResolver[ID, FILE, FOLDER] {
    override def resolve(components: Seq[String],
                         fs: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                         cryptConfig: CryptConfig)
                        (implicit system: ActorSystem[_]): Operation[ID] = Operation { httpSender =>
      implicit val ec: ExecutionContextExecutor = system.executionContext
      resolverActor.ask[PathLookupResult[ID]] { ref =>
        PathLookupRequest(components, fs, httpSender, cryptConfig, ref)
      } flatMap {
        _.resultFuture
      }
    }

    override def close(): Unit = {
      resolverActor ! StopActor()
    }
  }

  /**
   * Returns the behavior of a resolver actor that manages a cache with
   * resolved paths of the given size.
   *
   * @param cacheSize        the cache size
   * @param decryptChunkSize the chunk size for decrypt operations
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return the behavior of the resolver actor
   */
  def pathResolverActor[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](cacheSize: Int, decryptChunkSize: Int): Behavior[PathLookupCommand[ID, FILE, FOLDER]] =
    handleInitialPathResolveRequest(cacheSize, decryptChunkSize, Nil)

  /**
   * Stops the given path resolver actor. This function is normally not called
   * by an application, as the resolver actor is stopped automatically by the
   * resolver when its ''close()'' function is called.
   *
   * @param actor the actor to be stopped
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  def stopPathResolverActor[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](actor: ActorRef[PathLookupCommand[ID, FILE, FOLDER]]): Unit = {
    actor ! StopActor()
  }

  /**
   * Handler function of the path resolver actor that mainly wait for the
   * initial request to arrive. The initial request is special as the root ID
   * of the associated file system has to be resolved first. (As this is needed
   * for every resolve operation, it is done once and then cached.) Note that
   * this cannot be done beforehand, as the file system becomes only known when
   * a request arrives. This implementation expects that always the same file
   * system is used in requests.
   *
   * @param cacheSize        the cache size
   * @param pendingRequests  stores pending requests that can only be handled
   *                         when the root folder ID is available
   * @param decryptChunkSize the chunk size for decrypt operations
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return the behavior to handle the initial request
   */
  private def handleInitialPathResolveRequest[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](cacheSize: Int, decryptChunkSize: Int,
                                pendingRequests: List[PathLookupRequest[ID, FILE, FOLDER]]):
  Behavior[PathLookupCommand[ID, FILE, FOLDER]] = Behaviors.receivePartial {
    case (ctx, request: PathLookupRequest[ID, FILE, FOLDER]) =>
      if (pendingRequests.isEmpty) {
        implicit val system: ActorSystem[_] = ctx.system
        val futRootID = request.fileSystem.rootID.run(request.httpSender)
        ctx.pipeToSelf(futRootID) {
          GotRootID(request, _)
        }
      }
      handleInitialPathResolveRequest(cacheSize, decryptChunkSize, request :: pendingRequests)

    case (ctx, GotRootID(_, Success(rootID))) =>
      ctx.log.info("Resolved file system root ID to {}.", rootID)
      pendingRequests foreach (ctx.self ! _)
      val chunkSize = math.min(cacheSize, if (decryptChunkSize <= 0) cacheSize else decryptChunkSize)
      if (decryptChunkSize > cacheSize) {
        ctx.log.warn("cryptChunkSize exceeds cache size. Setting it to {}.", chunkSize)
      }
      handlePathResolveRequests(rootID, chunkSize, LRUCache(cacheSize), Map.empty)

    case (ctx, GotRootID(_, Failure(exception))) =>
      ctx.log.error("Could not resolve ID of root folder.", exception)
      pendingRequests.foreach { req =>
        req.client ! PathLookupErrorResult(exception)
      }
      handleInitialPathResolveRequest(cacheSize, decryptChunkSize, Nil)

    case (ctx, StopActor()) =>
      stopResolverActor(ctx)
  }

  /**
   * The default handler function of the path resolver actor. Here the requests
   * to resolve paths are handled. If a path to resolve is found in the cache,
   * it can be returned directly. Otherwise, for each path component not yet
   * resolved, a new actor is spawned to read the content of this folder and
   * decrypt its names. When the result of this operation arrives, processing
   * of the request can continue.
   *
   * @param rootID           the ID of the root folder
   * @param decryptChunkSize the chunk size for decrypt operations
   * @param cache            the cache for already resolved paths
   * @param pendingRequests  stores requests waiting for folder results
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return the default request processing behavior
   */
  private def handlePathResolveRequests[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](rootID: ID, decryptChunkSize: Int, cache: LRUCache[String, ID],
                                pendingRequests: Map[ID, List[RequestProgress[ID, FILE, FOLDER]]]):
  Behavior[PathLookupCommand[ID, FILE, FOLDER]] = Behaviors.receivePartial {
    case (ctx, request: PathLookupRequest[ID, FILE, FOLDER]) =>
      ctx.log.debug("PathLookupRequest for path {}.", request.pathComponents.mkString("/"))
      val progress = RequestProgress(request, UriEncodingHelper.fromComponentsWithEncode(request.pathComponents),
        rootID, Nil)
      val nextStep = handleStep(rootID, List(progress), decryptChunkSize, cache, pendingRequests, ctx)
      handlePathResolveRequests(rootID, decryptChunkSize, nextStep.nextCache, nextStep.nextProgress)

    case (ctx, result: ResolveFolderResult[ID, FILE, FOLDER]) =>
      val prefix = UriEncodingHelper.withTrailingSeparator(result.request.prefix)
      val encodedElementIDs = result.elementIDs.map(e => (UriEncodingHelper.encode(e._1), e._2))
      val updatedCache = addToCache(cache, encodedElementIDs, prefix)
      val (success, remaining) = pendingRequests.getOrElse(result.request.folderID, Nil) partition { progress =>
        encodedElementIDs.contains(progress.remainingComponents.head)
      }

      val updatedPending = pendingRequests + (result.request.folderID -> remaining)
      val nextStep = handleStep(rootID, success, decryptChunkSize, updatedCache, updatedPending, ctx)
      handlePathResolveRequests(rootID, decryptChunkSize, nextStep.nextCache, nextStep.nextProgress)

    case (_, result: ResolveFolderError[ID, FILE, FOLDER]) =>
      val errResult = PathLookupErrorResult[ID](result.exception)
      pendingRequests.getOrElse(result.request.folderID, Nil) foreach { progress =>
        progress.request.client ! errResult
      }
      handlePathResolveRequests(rootID, decryptChunkSize, cache, pendingRequests - result.request.folderID)

    case (_, ResolveFolderDone(request)) =>
      val failed = pendingRequests.getOrElse(request.folderID, Nil)
      failed foreach { progress =>
        val exception =
          new IllegalArgumentException("Could not resolve all path components. Remaining components: " +
            progress.remainingComponents.map(UriEncodingHelper.decode).mkString(","))
        progress.request.client ! PathLookupErrorResult(exception)
      }
      handlePathResolveRequests(rootID, decryptChunkSize, cache, pendingRequests - request.folderID)

    case (ctx, StopActor()) =>
      stopResolverActor(ctx)
  }

  /**
   * The handler function of the folder resolver actor. Such an actor is
   * created each time the content of a folder must be processed. The actor
   * loads the folder's content from the file system, decrypts all element
   * names, and sends a response back to the caller. Then it terminates itself.
   *
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return the behavior of the resolve folder actor
   */
  private def handleFolderResolveRequest[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](): Behavior[ResolveFolderCommand[ID, FILE, FOLDER]] =
    Behaviors.receivePartial {
      case (ctx, req: ResolveFolderRequest[ID, FILE, FOLDER]) =>
        ctx.log.info("Requesting content of folder {}.", req.folderID)
        implicit val system: ActorSystem[_] = ctx.system
        val futContent = req.pathRequest.fileSystem.folderContent(req.folderID).run(req.pathRequest.httpSender)
        ctx.pipeToSelf(futContent) {
          GotFolderContent(req, _)
        }
        Behaviors.same

      case (ctx, GotFolderContent(request, Success(content))) =>
        ctx.log.info("Decrypting element names of folder {}.", request.folderID)
        (content.folders ++ content.files).grouped(request.chunkSize) foreach { chunk =>
          val result = ResolveFolderResult(request,
            decryptElementNames(request.pathRequest.cryptConfig, chunk, ctx.log))
          request.client ! result
        }
        request.client ! ResolveFolderDone(request)
        Behaviors.stopped

      case (ctx, GotFolderContent(request, Failure(exception))) =>
        ctx.log.error("Failed to retrieve content for folder {}.", request.folderID, exception)
        val result = ResolveFolderError(request, exception)
        request.client ! result
        Behaviors.stopped
    }

  /**
   * Returns a map with the IDs and the decrypted names of the given folder
   * elements.
   *
   * @param config   the configuration for decryption
   * @param elements the elements to process
   * @param log      the logger
   * @tparam ID   the ID type
   * @tparam ELEM the type of elements
   * @return a map with decrypted element names
   */
  private def decryptElementNames[ID, ELEM <: Model.Element[ID]](config: CryptConfig, elements: Map[ID, ELEM],
                                                                 log: Logger): Map[String, ID] =
    elements.foldRight(Map.empty[String, ID]) { (e, map) =>
      CryptService.decryptTextFromBase64(config.algorithm, config.keyDecrypt, e._2.name)(config.secRandom) match {
        case Success(decryptedName) => map + (decryptedName -> e._1)
        case Failure(exception) =>
          log.warn("Ignoring file '{}', since its name cannot be decrypted.", e._2.name)
          log.debug("Decryption of file name failed.", exception)
          map
      }
    }

  /**
   * Returns an updated cache to which the given resolved element names have
   * been added.
   *
   * @param cache    the original cache
   * @param elements the elements to add
   * @param prefix   the common prefix for all keys
   * @tparam ID the ID type
   * @return the updated cache
   */
  private def addToCache[ID](cache: LRUCache[String, ID], elements: Map[String, ID], prefix: String):
  LRUCache[String, ID] =
    cache.put(elements.toList.map(e => (prefix + e._1, e._2)): _*)

  /**
   * Handles a step of a resolve operation that affects new requests or the
   * requests waiting for the content of a specific folder. This function
   * checks whether any of the requests are now complete; those are then
   * answered. For the others, the next resolve step is prepared, i.e. they are
   * added to the pending map, and requests to resolve folders are triggered.
   *
   * @param rootID           the ID of the root folder
   * @param progressList     the list with ''RequestProgress'' objects to handle
   * @param decryptChunkSize the chunk size for decrypt operations
   * @param cache            the current cache
   * @param pending          the current map with pending folder requests
   * @param ctx              the context of this actor
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return an updated ''ResolveStep'' with the next actor state
   */
  private def handleStep[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](rootID: ID, progressList: List[RequestProgress[ID, FILE, FOLDER]],
                                decryptChunkSize: Int, cache: LRUCache[String, ID],
                                pending: Map[ID, List[RequestProgress[ID, FILE, FOLDER]]],
                                ctx: ActorContext[PathLookupCommand[ID, FILE, FOLDER]]):
  ResolveStep[ID, FILE, FOLDER] = {
    val initStep = ResolveStep(cache, pending, Nil)
    val nextStep = progressList.foldLeft(initStep) { (step, progress) =>
      val (updatedProgress, updatedStep) = updateProgress(rootID, progress, step, decryptChunkSize, ctx)
      if (updatedProgress.isComplete) {
        updatedProgress.request.client ! PathLookupSuccessResult(updatedProgress.resolvedID)
      }
      updatedStep
    }

    nextStep.folderRequests foreach { request =>
      val folderResolverActor = ctx.spawnAnonymous(handleFolderResolveRequest[ID, FILE, FOLDER]())
      folderResolverActor ! request
    }
    nextStep
  }

  /**
   * Updates the given progress object with recent information from the cache.
   * This function checks, which path components still need to be resolved.
   * Ideally, the resolve operation can be completed if the whole path is now
   * available. Otherwise, the function determines the next path component to
   * resolve and updates the resolve step accordingly.
   *
   * @param rootID           the ID of the root folder
   * @param progress         the ''RequestProgress'' to update
   * @param step             the current step
   * @param decryptChunkSize the chunk size for decrypt operations
   * @param ctx              the context of this actor
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return a tuple with the updated progress and resolve step
   */
  private def updateProgress[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](rootID: ID, progress: RequestProgress[ID, FILE, FOLDER],
                                step: ResolveStep[ID, FILE, FOLDER], decryptChunkSize: Int,
                                ctx: ActorContext[PathLookupCommand[ID, FILE, FOLDER]]):
  (RequestProgress[ID, FILE, FOLDER], ResolveStep[ID, FILE, FOLDER]) = {
    @tailrec
    def nextComponentToResolve(path: String, remaining: List[String], cache: LRUCache[String, ID]):
    (RequestProgress[ID, FILE, FOLDER], ResolveStep[ID, FILE, FOLDER]) =
      if (path.isEmpty) {
        val nextProgress = progress.copy(resolvedID = rootID, remainingComponents = remaining)
        val nextStep = step.copy(nextCache = cache)
          .addProgress(nextProgress, UriEncodingHelper.UriSeparator, decryptChunkSize, ctx)
        (nextProgress, nextStep)
      } else {
        val (optID, nextCache) = cache.getLRU(path)
        optID match {
          case Some(id) =>
            val nextProgress = progress.copy(resolvedID = id, remainingComponents = remaining)
            val nextStep = step.copy(nextCache = nextCache)
              .addProgress(nextProgress, UriEncodingHelper.withTrailingSeparator(path), decryptChunkSize, ctx)
            (nextProgress, nextStep)

          case None =>
            val (parent, comp) = UriEncodingHelper.splitParent(path)
            nextComponentToResolve(parent, comp :: remaining, nextCache)
        }
      }

    nextComponentToResolve(progress.path, Nil, step.nextCache)
  }

  /**
   * Stops a path resolver actor after logging an info message.
   *
   * @param ctx the actor context
   * @tparam ID     the ID type
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @return the stopped behavior
   */
  private def stopResolverActor[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](ctx: ActorContext[PathLookupCommand[ID, FILE, FOLDER]]):
  Behavior[PathLookupCommand[ID, FILE, FOLDER]] = {
    ctx.log.info("Stopping PathResolverActor.")
    Behaviors.stopped
  }
}
