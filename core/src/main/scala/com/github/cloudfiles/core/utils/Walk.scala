/*
 * Copyright 2020-2025 The Developers Team.
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

package com.github.cloudfiles.core.utils

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.{FileSystem, Model}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.stream.{Attributes, Outlet, SourceShape}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, OutHandler}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * A module providing functionality to iterate over a folder structure in a
 * [[FileSystem]].
 *
 * This module offers functions supporting walk functionality in different
 * orders. The functions expect the [[FileSystem]] to iterate over, the HTTP
 * actor to interact with the file system, and the ID of the root folder. They
 * return a [[Source]] with [[Model.Element]] objects for the items that where
 * encountered in this folder structure.
 */
object Walk {
  /**
   * The size of a chunk when resolving the contents of folders during the
   * iteration.
   */
  private val ResolveFoldersChunkSize = 8

  /**
   * Type definition of a function that can be specified when creating a walk
   * source to manipulate the elements to be processed for a folder.
   * The walk source calls this function whenever it starts processing of a new
   * folder with a list of this folder's files and subfolders. The function can
   * then perform arbitrary manipulations on this list, e.g. filter out
   * specific elements or apply sorting. The resulting list is then used as
   * basis for the iteration.
   */
  type TransformFunc[ID] = List[Model.Element[ID]] => List[Model.Element[ID]]

  /**
   * Type definition of a function that can extract data from a parent folder.
   * The function is passed the folder element and returns an [[Option]] with
   * the data to store for this parent. If the result is not ''None'', it is
   * stored for this element.
   */
  type ParentDataFunc[ID, DATA] = Model.Folder[ID] => Option[DATA]

  /**
   * A data class to combine an element encountered during an iteration with
   * arbitrary data collected from its parent folders up to the start folder of
   * the iteration. This class is used by iteration functions that return
   * additional data for each element based on its position in the folder
   * structure iterated over.
   *
   * @param element    the current element
   * @param parentData a list with the data obtained from the parent folders;
   *                   the ''head'' element is the data from the direct parent,
   *                   the next element is from this folder's parent and so on
   * @tparam ID   the type of the ID of elements
   * @tparam DATA the type of the data obtained from parents
   */
  case class ElementWithParentData[+ID, +DATA](element: Model.Element[ID],
                                               parentData: List[DATA])

  /**
   * A data class that collects the mandatory and optional parameters
   * supported by a walk operation. For the optional parameters, the class
   * provides meaningful default values.
   *
   * @param fileSystem the [[FileSystem]] that is the target of the iteration
   * @param httpActor  the actor for sending HTTP requests
   * @param rootID     the ID of the root folder of the iteration
   * @param transform  a [[TransformFunc]] to manipulate the iteration
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   */
  case class WalkConfig[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                rootID: ID,
                                transform: TransformFunc[ID] = identityTransform[ID])

  /**
   * Returns a [[Source]] to iterate over a folder structure in breadth-first
   * search order.
   *
   * @param walkConfig the config for the walk operation
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return the [[Source]] with the encountered elements
   */
  def bfsSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](walkConfig: WalkConfig[ID, FILE, FOLDER])
                               (implicit system: ActorSystem[_]): Source[Model.Element[ID], NotUsed] =
    bfsSourceWithParentData(walkConfig, noParentDataFunc[ID])
      .map(_.element)

  /**
   * Returns a [[Source]] to iterate over a folder structure in breadth-first
   * search order and to collect information about parent folders. This
   * function works like [[bfsSource]], but it accepts an additional function
   * to extract information from an element's parent folders. The source emits
   * [[ElementWithParentData]] objects allowing access to the collected parent
   * data. This is useful if the full path from the start folder of the
   * iteration to the element is relevant.
   *
   * @param walkConfig     the config for the walk operation
   * @param parentDataFunc the [[ParentDataFunc]] to extract information about
   *                       parent folders
   * @param system         the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type of the data extracted from parents
   * @return the [[Source]] with the encountered elements
   */
  def bfsSourceWithParentData[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](walkConfig: WalkConfig[ID, FILE, FOLDER],
                                      parentDataFunc: ParentDataFunc[ID, DATA])
                                     (implicit system: ActorSystem[_]):
  Source[ElementWithParentData[ID, DATA], NotUsed] = {
    val walkSource = new WalkSource(walkConfig, parentDataFunc) {
      override type WalkState = BfsState[ID, DATA]

      override protected val walkFunc: WalkFunc = walkBfs

      override protected def initWalk(rootFolder: Model.FolderContent[ID, FILE, FOLDER]): (WalkState, Iterable[ID]) = {
        val initState = BfsState(
          List.empty,
          Queue((rootFolder.folderID, List.empty[DATA]))
        )
        val foldersToResolve = rootFolder.folders.keySet
        (initState, foldersToResolve)
      }

      /**
       * A concrete ''WalkFunc'' for iterating over a folder structure in
       * breadth-first search.
       *
       * @param state          the current walk state
       * @param folderState    the current state of resolved folders
       * @param transform      the transformer function
       * @param parentDataFunc the parent data extraction function
       * @return information to continue the walk operation
       */
      private def walkBfs(state: BfsState[ID, DATA],
                          folderState: WalkFolderState[ID, FILE, FOLDER],
                          transform: TransformFunc[ID],
                          parentDataFunc: ParentDataFunc[ID, DATA]): WalkFuncResult = {
        state.currentFolderElements match {
          case h :: t =>
            val nextState = state.copy(currentFolderElements = t)
            WalkProceed(nextState, Some(h), folderState)

          case _ =>
            state.foldersToProcess.headOption match {
              case Some((folderID, parentData)) =>
                withFolder(folderState, folderID) { folder =>
                  val (_, nextQueue) = state.foldersToProcess.dequeue
                  val subFolders = folder.folders.values.toList
                  val subFolderIDs = subFolders.map(_.id).toSet
                  val subFiles = folder.files.values.toList
                  val folderElements = transform(subFiles ::: subFolders)
                    .map(e => ElementWithParentData(e, parentData))
                  val folderElementsTail = if (folderElements.isEmpty) Nil else folderElements.tail
                  val subFoldersToProcess = folderElements.filter(e => subFolderIDs.contains(e.element.id)).map { e =>
                    val folderParentData = parentDataFunc(e.element.asInstanceOf[Model.Folder[ID]])
                      .fold(parentData)(data => data :: parentData)
                    (e.element.id, folderParentData)
                  }
                  val nextState = state.copy(
                    currentFolderElements = folderElementsTail,
                    foldersToProcess = nextQueue :++ subFoldersToProcess
                  )
                  val nextFolderState = folderState.withIterationResults(
                    toResolve = subFoldersToProcess.map(_._1),
                    processed = Some(folderID)
                  )
                  WalkProceed(nextState, folderElements.headOption, nextFolderState)
                }

              case None =>
                WalkComplete
            }
        }
      }
    }

    Source.fromGraph(walkSource)
  }

  /**
   * Returns a [[Source]] to iterate over a folder structure in depth-first
   * search order.
   *
   * @param walkConfig the config for the walk operation
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return the [[Source]] with the encountered elements
   */
  def dfsSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](walkConfig: WalkConfig[ID, FILE, FOLDER])
                               (implicit system: ActorSystem[_]): Source[Model.Element[ID], NotUsed] = {
    dfsSourceWithParentData(walkConfig, noParentDataFunc[ID])
      .map(_.element)
  }

  /**
   * Returns a [[Source]] to iterate over a folder structure in depth-first
   * search order and to collect information about parent folders. This
   * function works like [[bfsSource]], but it accepts an additional function
   * to extract information from an element's parent folders. The source emits
   * [[ElementWithParentData]] objects allowing access to the collected parent
   * data. This is useful if the full path from the start folder of the
   * iteration to the element is relevant.
   *
   * @param walkConfig     the config for the walk operation
   * @param parentDataFunc the [[ParentDataFunc]] to extract information about
   *                       parent folders
   * @param system         the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type of the data extracted from parents
   * @return the [[Source]] with the encountered elements
   */
  def dfsSourceWithParentData[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](walkConfig: WalkConfig[ID, FILE, FOLDER],
                                      parentDataFunc: ParentDataFunc[ID, DATA])
                                     (implicit system: ActorSystem[_]):
  Source[ElementWithParentData[ID, DATA], NotUsed] = {
    val walkSource = new WalkSource(walkConfig, parentDataFunc) {
      override type WalkState = DfsState[ID, DATA]

      override protected val walkFunc: WalkFunc = walkDfs

      override protected def initWalk(rootFolder: Model.FolderContent[ID, FILE, FOLDER]): (WalkState, Iterable[ID]) = {
        val (nextCurrent, optFirstFolderID) =
          prepareFolderForDfs(rootFolder, None, walkConfig.transform, parentDataFunc)
        val initState = DfsState(List(nextCurrent))
        val foldersToResolve = optFirstFolderID.fold(List.empty[ID])(f => List(f))
        (initState, foldersToResolve)
      }

      /**
       * A concrete ''WalkFunc'' for iterating over a folder structure in
       * depth-first search.
       *
       * @param state          the current walk state
       * @param folderState    the state of resolved folders
       * @param transform      the transformer function
       * @param parentDataFunc the parent data extraction function
       * @return information to continue the walk operation
       */
      private def walkDfs(state: DfsState[ID, DATA],
                          folderState: WalkFolderState[ID, FILE, FOLDER],
                          transform: TransformFunc[ID],
                          parentDataFunc: ParentDataFunc[ID, DATA]): WalkFuncResult = {
        state.activeFolders match {
          case current :: nextElements =>
            val (optNext, updatedCurrent) = current.processNextElement()
            optNext match {
              case optElem@Some(element) if !current.isFolder(element.element) =>
                val nextState = state.copy(activeFolders = updatedCurrent :: nextElements)
                WalkProceed(nextState, optElem, folderState)

              case optElem@Some(element) =>
                withFolder(folderState, element.element.id) { content =>
                  val (nextCurrent, optFirstFolderID) =
                    prepareFolderForDfs(content, Some(element), transform, parentDataFunc)
                  val nextState = state.copy(activeFolders = nextCurrent :: updatedCurrent :: nextElements)
                  val foldersToResolve = List(optFirstFolderID, updatedCurrent.nextFolderID).flatten
                  val nextFolderState = folderState.withIterationResults(toResolve = foldersToResolve)
                  WalkProceed(nextState, optElem, nextFolderState)
                }

              case None =>
                val nextState = state.copy(activeFolders = nextElements)
                val nextFolderState = folderState.withIterationResults(processed = Some(current.currentID))
                WalkProceed(nextState, None, nextFolderState)
            }

          case Nil =>
            WalkComplete
        }
      }
    }

    Source.fromGraph(walkSource)
  }

  /**
   * A data class to manage the state of folders to be resolved asynchronously
   * by a [[WalkSource]] instance.
   *
   * While the iteration over the folder structure is ongoing, the source - in
   * parallel - already fetches the content of folders that are about to be
   * iterated over. This class holds the information required for this purpose.
   *
   * @param rootFolder       the root folder of the iteration
   * @param resolvedFolders  a map with already resolved folders
   * @param foldersToResolve a collection with the IDs of folders to be
   *                         resolved next
   * @tparam ID     the type of IDs of elements
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class WalkFolderState[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](rootFolder: Model.FolderContent[ID, FILE, FOLDER],
                                resolvedFolders: Map[ID, Model.FolderContent[ID, FILE, FOLDER]],
                                foldersToResolve: Vector[ID]) {
    /**
     * Returns a flag whether the folder with the given ID has already been
     * resolved.
     *
     * @param folderID the ID of the folder in question
     * @return a flag whether this folder has been resolved
     */
    def isResolved(folderID: ID): Boolean =
      resolvedFolders.contains(folderID) || folderID == rootFolder.folderID

    /**
     * Returns the resolved folder content for the folder with the given ID or
     * fails if this folder has not yet been resolved.
     *
     * @param folderID the ID of the folder in question
     * @return the content of this folder
     */
    def getResolvedFolder(folderID: ID): Model.FolderContent[ID, FILE, FOLDER] =
      if (folderID == rootFolder.folderID) rootFolder
      else resolvedFolders(folderID)

    /**
     * Returns an updated [[WalkFolderState]] instance that has the given
     * collection of folder contents added to the managed map of contents.
     *
     * @param folders the collection of folder contents to add
     * @return the updated instance
     */
    def withResolvedFolders(folders: Iterable[Model.FolderContent[ID, FILE, FOLDER]]):
    WalkFolderState[ID, FILE, FOLDER] =
      copy(resolvedFolders = resolvedFolders ++ folders.map(c => c.folderID -> c))

    /**
     * Returns a [[WalkFolderState]] instance that is updated for the results
     * of a walk function. The walk function may generate new folders that have
     * to be resolved or complete the processing of a folder, in which case it
     * can be removed from the map.
     *
     * @param toResolve a collection with new folders to resolve
     * @param processed an optional ID of a folder that was processed
     * @return the updated instance
     */
    def withIterationResults(toResolve: Iterable[ID] = Nil, processed: Option[ID] = None):
    WalkFolderState[ID, FILE, FOLDER] =
      if (toResolve.isEmpty && processed.isEmpty) this
      else copy(
        foldersToResolve = foldersToResolve.appendedAll(toResolve),
        resolvedFolders = processed.fold(resolvedFolders)(resolvedFolders - _)
      )

    /**
     * Returns a tuple with a chunk of folder IDs that need to be resolved and
     * the updated instance which no longer contains the returned folders.
     *
     * @return a chunk of folder IDs to resolve and the updated instance
     */
    def getFoldersToResolve: (Vector[ID], WalkFolderState[ID, FILE, FOLDER]) =
      if (foldersToResolve.isEmpty) (Vector.empty, this)
      else {
        val (res, next) = foldersToResolve.splitAt(ResolveFoldersChunkSize)
        (res, copy(foldersToResolve = next))
      }
  }

  /**
   * A data class holding the state of an iteration in BFS order.
   *
   * @param currentFolderElements the elements from the current
   * @param foldersToProcess      the IDs of folders to be processed next
   * @tparam ID   the type of IDs of elements
   * @tparam DATA the type of parent data
   */
  private case class BfsState[ID, DATA](currentFolderElements: List[ElementWithParentData[ID, DATA]],
                                        foldersToProcess: Queue[(ID, List[DATA])])

  /**
   * A data class to store information about the currently processed folder in
   * depth-first search iteration order.
   *
   * @param currentID  the ID of the current folder
   * @param elements   the remaining list of elements to iterate over
   * @param folderIDs  a set with the IDs of folder elements
   * @param parentData the aggregated data extracted for parents
   * @tparam ID   the type of IDs of elements
   * @tparam DATA the type for parent data
   */
  private case class DfsCurrentFolder[ID, DATA](currentID: ID,
                                                elements: List[Model.Element[ID]],
                                                folderIDs: Set[ID],
                                                parentData: List[DATA]) {
    /**
     * Returns a flag whether the given element is a folder.
     *
     * @param element the element in question
     * @return '''true''' if this element is a folder; '''false''' otherwise
     */
    def isFolder(element: Model.Element[ID]): Boolean = folderIDs.contains(element.id)

    /**
     * Returns an [[Option]] with the ID of the next folder in the list of
     * elements to be processed. This is used to determine which folder content
     * should be retrieved next.
     *
     * @return an optional ID of the next folder in the processing list
     */
    def nextFolderID: Option[ID] =
      elements.collectFirst {
        case e if isFolder(e) => e.id
      }

    /**
     * Returns an updated instance with the current element dropped and an
     * [[Option]] with this current element. With this function, a single step
     * of the iteration is performed.
     *
     * @return a tuple with the optional next element and the updated folder
     */
    def processNextElement(): (Option[ElementWithParentData[ID, DATA]], DfsCurrentFolder[ID, DATA]) =
      elements match {
        case h :: t => (Some(ElementWithParentData(h, parentData)), copy(elements = t))
        case Nil => (None, this)
      }
  }

  /**
   * A data class holding the state of an iteration in DFS order.
   *
   * @param activeFolders the folders that are currently processed
   * @tparam ID   the type of IDs of elements
   * @tparam DATA the type for parent data
   */
  private case class DfsState[ID, DATA](activeFolders: List[DfsCurrentFolder[ID, DATA]])

  /**
   * Implementation of a [[Source]] that produces the data of a walk operation
   * with the help of a [[WalkFunc]] that determines the order in which the
   * folder structure is processed.
   *
   * @param walkConfig the config for the walk operation
   * @param parentData the function to extract parent data
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type for parent data
   */
  private abstract class WalkSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](walkConfig: WalkConfig[ID, FILE, FOLDER],
                                      parentData: ParentDataFunc[ID, DATA])
                                     (implicit system: ActorSystem[_])
    extends GraphStage[SourceShape[ElementWithParentData[ID, DATA]]] {
    /** Type definition for the specific state of the iteration. */
    type WalkState

    /**
     * Definition of a function that controls the iteration over the folder
     * structure. The function operates on a specific state that is managed by
     * the source implementation. It is passed such a state object, the current
     * state of resolved [[Model.FolderContent]] objects, the [[TransformFunc]]
     * to apply on folder elements, and the function to extract parent data. It
     * returns a [[WalkFuncResult]] object that instructs the source how to
     * proceed with the iteration.
     */
    type WalkFunc =
      (WalkState, WalkFolderState[ID, FILE, FOLDER], TransformFunc[ID], ParentDataFunc[ID, DATA]) => WalkFuncResult

    /**
     * A trait representing the root of a hierarchy for different results of the
     * function that handles the iteration. This allows to distinguish between
     * different states of the iteration and how this source implementation needs
     * to behave.
     */
    sealed trait WalkFuncResult

    /**
     * A special [[WalkFuncResult]] for the case that the iteration can continue.
     * The source implementation has to handle the provided data (which can be
     * empty) and then invokes the walk function again.
     *
     * @param nextState       the next iteration state
     * @param optNextElement  an optional element to push downstream
     * @param nextFolderState a list with the IDs of folders whose content can
     *                        be resolved
     */
    case class WalkProceed(nextState: WalkState,
                           optNextElement: Option[ElementWithParentData[ID, DATA]],
                           nextFolderState: WalkFolderState[ID, FILE, FOLDER])
      extends WalkFuncResult

    /**
     * A special [[WalkFuncResult]] for the case that the iteration can currently
     * not continue because the content of folders needs to be resolved first.
     * The source implementation will call the walk function again only after new
     * folder content results are available.
     */
    case object WalkFoldersPending extends WalkFuncResult

    /**
     * A special [[WalkFuncResult]] to indicate that the iteration is now done.
     * This causes the source implementation to complete the stream.
     */
    case object WalkComplete extends WalkFuncResult

    private val out: Outlet[ElementWithParentData[ID, DATA]] = Outlet("WalkSource")

    override def shape: SourceShape[ElementWithParentData[ID, DATA]] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {

        import walkConfig._

        /** Callback for the asynchronous operation to load folder contents. */
        private val resolvedFoldersCallback = getAsyncCallback(onFoldersResolved)

        /** The current state of the iteration. */
        private var currentState: WalkState = _

        /** Stores the current state of resolved folders. */
        private var folderState = WalkFolderState(
          rootFolder = null,
          foldersToResolve = Vector(rootID),
          resolvedFolders = Map.empty[ID, Model.FolderContent[ID, FILE, FOLDER]]
        )

        /**
         * A flag whether currently the content of folders is resolved.
         */
        private var resolveInProgress = false

        /** Flag whether downstream has pulled for data. */
        private var pulled = false

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            pulled = true
            continueWalking()
          }
        })

        override def preStart(): Unit = {
          loadFolderContent()
        }

        /**
         * Processes a chunk of folders that need to be resolved and calls the
         * file system to load their contents.
         *
         * @return '''true''' if folders are pending to be loaded; '''false'''
         *         otherwise
         */
        private def loadFolderContent(): Boolean = {
          if (!resolveInProgress) {
            val (toResolve, nextState) = folderState.getFoldersToResolve
            if (toResolve.nonEmpty) {
              implicit val ec: ExecutionContext = system.executionContext
              Future.sequence(folderState.foldersToResolve.take(ResolveFoldersChunkSize)
                .map { folderID =>
                  val operation = fileSystem.folderContent(folderID)
                  operation.run(httpActor)
                }).onComplete(resolvedFoldersCallback.invoke)
              resolveInProgress = true
              folderState = nextState
              true
            } else false
          } else false
        }

        /**
         * Handles a result of a resolve operation on folders. Stores the
         * folder contents and continues with the iteration if possible. In
         * case of a failure, this [[Source]] completes with an error.
         *
         * @param triedContents the [[Try]] with the resolved folder contents
         */
        private def onFoldersResolved(triedContents: Try[Vector[Model.FolderContent[ID, FILE, FOLDER]]]): Unit =
          triedContents match {
            case Success(contents) =>
              resolveInProgress = false
              if (currentState == null) {
                // The root folder has been resolved, now the iteration can actually start.
                val (initState, folderIDs) = initWalk(contents.head)
                currentState = initState
                folderState = folderState.copy(
                  rootFolder = contents.head,
                  foldersToResolve = folderIDs.toVector
                )
              } else {
                folderState = folderState.withResolvedFolders(contents.toList)
              }

              loadFolderContent() // Continue with the next chunk if available.
              continueWalking()
            case Failure(exception) =>
              failStage(exception)
          }

        /**
         * Checks whether currently data is available that can be pushed
         * downstream by invoking the walk function. All necessary steps are
         * done to continue with the iteration.
         */
        @tailrec private def continueWalking(): Unit = {
          if (pulled && currentState != null) {
            val actionTaken = walkFunc(currentState, folderState, transform, parentData) match {
              case WalkProceed(nextState, optData, nextFolderState) =>
                currentState = nextState
                folderState = nextFolderState
                optData match {
                  case Some(data) =>
                    push(out, data)
                    pulled = false
                    loadFolderContent()
                    true
                  case None =>
                    loadFolderContent()
                }

              case WalkFoldersPending =>
                true // Just do nothing and wait until folders are resolved.

              case WalkComplete =>
                completeStage()
                true
            }

            if (!actionTaken) continueWalking()
          }
        }
      }

    /**
     * Returns the function that controls the iteration. This function is
     * invoked repeatedly to obtain the elements to pass downstream.
     */
    protected val walkFunc: WalkFunc

    /**
     * Initializes the iteration. The base class calls this method after
     * resolving the root folder. A concrete implementation returns the initial
     * walk state and a list with folder IDs to be resolved next.
     *
     * @param rootFolder the root folder for the iteration
     * @return the initial [[WalkState]] and folders to resolve
     */
    protected def initWalk(rootFolder: Model.FolderContent[ID, FILE, FOLDER]): (WalkState, Iterable[ID])

    /**
     * Checks whether the given folder is available in the current folder
     * state and invokes the processing function if this is the case.
     * Otherwise, the result [[WalkFoldersPending]] is returned. This function
     * simplifies the handling of resolved folders in subclasses.
     *
     * @param folderState the current [[WalkFolderState]]
     * @param folderID    the ID of the folder to process
     * @param process     a function that processes the folder and yields a result
     * @return the result of the processing
     */
    protected def withFolder(folderState: WalkFolderState[ID, FILE, FOLDER], folderID: ID)
                            (process: Model.FolderContent[ID, FILE, FOLDER] => WalkFuncResult): WalkFuncResult =
      if (folderState.isResolved(folderID)) process(folderState.getResolvedFolder(folderID))
      else WalkFoldersPending
  }

  /**
   * Creates a new [[DfsCurrentFolder]] object for the given folder to continue
   * the iteration with this element. Also, returns an [[Option]] with the
   * first folder ID in the list of elements in this folder, which has to be
   * resolved next.
   *
   * @param content          the content of the next current folder
   * @param optParentElement the optional element representing the folder; this
   *                         is ''None'' for the initial state
   * @param transform        the transformation function
   * @param parentDataFunc   the function to extract data about parents
   * @return a tuple with the new current DFS folder and an optional ID of a
   *         folder whose content should be retrieved
   */
  private def prepareFolderForDfs[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](content: Model.FolderContent[ID, FILE, FOLDER],
                                      optParentElement: Option[ElementWithParentData[ID, DATA]],
                                      transform: TransformFunc[ID],
                                      parentDataFunc: ParentDataFunc[ID, DATA]):
  (DfsCurrentFolder[ID, DATA], Option[ID]) = {
    val nextParentData = optParentElement.map { parentElement =>
      val parentFolder = parentElement.element.asInstanceOf[Model.Folder[ID]]
      parentDataFunc(parentFolder).fold(parentElement.parentData) { data =>
        data :: parentElement.parentData
      }
    }.getOrElse(List.empty)

    val nextFolder = DfsCurrentFolder[ID, DATA](
      content.folderID,
      transform(content.files.values.toList ::: content.folders.values.toList),
      content.folders.keySet,
      nextParentData
    )
    (nextFolder, nextFolder.nextFolderID)
  }

  /**
   * Returns a [[TransformFunc]] which just returns the elements to be iterated
   * over without any changes. This is used as default if the user does not
   * specify a transformer function.
   *
   * @tparam ID the type of IDs of elements
   * @return the identity transformer function
   */
  private def identityTransform[ID]: TransformFunc[ID] = lst => lst

  /**
   * Returns a [[ParentDataFunc]] to be used if no parent data is needed. The
   * function always returns ''None''.
   *
   * @tparam ID the type of IDs of elements
   * @return the [[ParentDataFunc]] returning always ''None''
   */
  private def noParentDataFunc[ID]: ParentDataFunc[ID, Unit] = _ => None
}
