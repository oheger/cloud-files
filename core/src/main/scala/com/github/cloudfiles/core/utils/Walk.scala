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
   * Returns a [[Source]] to iterate over a folder structure in breadth-first
   * search order.
   *
   * @param fileSystem the [[FileSystem]] that is the target of the iteration
   * @param httpActor  the actor for sending HTTP requests
   * @param rootID     the ID of the root folder of the iteration
   * @param transform  a [[TransformFunc]] to manipulate the iteration
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return the [[Source]] with the encountered elements
   */
  def bfsSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                rootID: ID,
                                transform: TransformFunc[ID] = identityTransform[ID])
                               (implicit system: ActorSystem[_]): Source[Model.Element[ID], NotUsed] =
    bfsSourceWithParentData(fileSystem, httpActor, rootID, noParentDataFunc[ID], transform)
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
   * @param fileSystem     the [[FileSystem]] that is the target of the iteration
   * @param httpActor      the actor for sending HTTP requests
   * @param rootID         the ID of the root folder of the iteration
   * @param parentDataFunc the [[ParentDataFunc]] to extract information about
   *                       parent folders
   * @param transform      a [[TransformFunc]] to manipulate the iteration
   * @param system         the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type of the data extracted from parents
   * @return the [[Source]] with the encountered elements
   */
  def bfsSourceWithParentData[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                      httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                      rootID: ID,
                                      parentDataFunc: ParentDataFunc[ID, DATA],
                                      transform: TransformFunc[ID] = identityTransform[ID])
                                     (implicit system: ActorSystem[_]):
  Source[ElementWithParentData[ID, DATA], NotUsed] = {
    val walkSource = new WalkSource(fileSystem, httpActor, rootID, transform, parentDataFunc) {
      override type WalkState = BfsState[ID, FILE, FOLDER, DATA]

      override protected val walkFunc: WalkFunc[ID, FILE, FOLDER, DATA, WalkState] = walkBfs

      override protected def initWalk(rootFolder: Model.FolderContent[ID, FILE, FOLDER]): (WalkState, Iterable[ID]) = {
        val initState = BfsState(
          List.empty,
          Queue((rootFolder.folderID, List.empty[DATA])),
          Map(rootFolder.folderID -> rootFolder)
        )
        val foldersToResolve = rootFolder.folders.keySet
        (initState, foldersToResolve)
      }
    }

    Source.fromGraph(walkSource)
  }

  /**
   * Returns a [[Source]] to iterate over a folder structure in depth-first
   * search order.
   *
   * @param fileSystem the [[FileSystem]] that is the target of the iteration
   * @param httpActor  the actor for sending HTTP requests
   * @param rootID     the ID of the root folder of the iteration
   * @param transform  a [[TransformFunc]] to manipulate the iteration
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return the [[Source]] with the encountered elements
   */
  def dfsSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                rootID: ID,
                                transform: TransformFunc[ID] = identityTransform[ID])
                               (implicit system: ActorSystem[_]): Source[Model.Element[ID], NotUsed] = {
    dfsSourceWithParentData(fileSystem, httpActor, rootID, noParentDataFunc[ID], transform)
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
   * @param fileSystem     the [[FileSystem]] that is the target of the iteration
   * @param httpActor      the actor for sending HTTP requests
   * @param rootID         the ID of the root folder of the iteration
   * @param parentDataFunc the [[ParentDataFunc]] to extract information about
   *                       parent folders
   * @param transform      a [[TransformFunc]] to manipulate the iteration
   * @param system         the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type of the data extracted from parents
   * @return the [[Source]] with the encountered elements
   */
  def dfsSourceWithParentData[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                      httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                      rootID: ID,
                                      parentDataFunc: ParentDataFunc[ID, DATA],
                                      transform: TransformFunc[ID] = identityTransform[ID])
                                     (implicit system: ActorSystem[_]):
  Source[ElementWithParentData[ID, DATA], NotUsed] = {
    val walkSource = new WalkSource(fileSystem, httpActor, rootID, transform, parentDataFunc) {
      override type WalkState = DfsState[ID, FILE, FOLDER, DATA]

      override protected val walkFunc: WalkFunc[ID, FILE, FOLDER, DATA, WalkState] = walkDfs

      override protected def initWalk(rootFolder: Model.FolderContent[ID, FILE, FOLDER]): (WalkState, Iterable[ID]) = {
        val (nextCurrent, optFirstFolderID) = prepareFolderForDfs(rootFolder, None, transform, parentDataFunc)
        val initState = DfsState(
          activeFolders = List(nextCurrent),
          resolvedFolders = Map(rootFolder.folderID -> rootFolder)
        )
        val foldersToResolve = optFirstFolderID.fold(List.empty[ID])(f => List(f))
        (initState, foldersToResolve)
      }
    }

    Source.fromGraph(walkSource)
  }

  /**
   * A trait representing the root of a hierarchy for different results of the
   * function that handles the iteration. This allows to distinguish between
   * different states of the iteration and how this source implementation needs
   * to behave.
   *
   * @tparam ID    the ID type of the elements in the iteration
   * @tparam STATE the type of the iteration state
   * @tparam DATA  the type of the data extracted from parents
   */
  private sealed trait WalkFuncResult[+ID, +STATE, +DATA]

  /**
   * A special [[WalkFuncResult]] for the case that the iteration can continue.
   * The source implementation has to handle the provided data (which can be
   * empty) and then invokes the walk function again.
   *
   * @param nextState        the next iteration state
   * @param optNextElement   an optional element to push downstream
   * @param foldersToResolve a list with the IDs of folders whose content can
   *                         be resolved
   * @tparam ID    the ID type of the elements in the iteration
   * @tparam STATE the type of the iteration state
   * @tparam DATA  the type of the data extracted from parents
   */
  private case class WalkProceed[ID, STATE, DATA](nextState: STATE,
                                                  optNextElement: Option[ElementWithParentData[ID, DATA]],
                                                  foldersToResolve: List[ID]) extends WalkFuncResult[ID, STATE, DATA]

  /**
   * A special [[WalkFuncResult]] for the case that the iteration can currently
   * not continue because the content of folders needs to be resolved first.
   * The source implementation will call the walk function again only after new
   * folder content results are available.
   */
  private case object WalkFoldersPending extends WalkFuncResult[Nothing, Nothing, Nothing]

  /**
   * A special [[WalkFuncResult]] to indicate that the iteration is now done.
   * This causes the source implementation to complete the stream.
   */
  private case object WalkComplete extends WalkFuncResult[Nothing, Nothing, Nothing]

  /**
   * Definition of a function that controls the iteration over the folder 
   * structure. The function operates on a specific state that is managed by 
   * the source implementation. It is passed such a state object, a list with
   * [[Model.FolderContent]] objects that have been resolved from the file
   * system, the [[TransformFunc]] to apply on folder elements, and the
   * function to extract parent data. It returns a [[WalkFuncResult]] object
   * that instructs the source how to proceed with the iteration.
   */
  private type WalkFunc[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID], DATA, STATE] =
    (STATE, Iterable[Model.FolderContent[ID, FILE, FOLDER]], TransformFunc[ID], ParentDataFunc[ID, DATA]) =>
      WalkFuncResult[ID, STATE, DATA]

  /**
   * A data class holding the state of an iteration in BFS order.
   *
   * @param currentFolderElements the elements from the current
   * @param foldersToProcess      the IDs of folders to be processed next
   * @param resolvedFolders       a map with the already resolved folder
   *                              contents; when a folder is about to be
   *                              processed that has not yet been resolved,
   *                              iteration has to wait until it becomes
   *                              available
   * @tparam ID     the type of IDs of elements
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @tparam DATA   the type of parent data
   */
  private case class BfsState[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](currentFolderElements: List[ElementWithParentData[ID, DATA]],
                                      foldersToProcess: Queue[(ID, List[DATA])],
                                      resolvedFolders: Map[ID, Model.FolderContent[ID, FILE, FOLDER]])

  /**
   * A data class to store information about the currently processed folder in
   * depth-first search iteration order.
   *
   * @param currentID  the ID of the current folder
   * @param elements   the remaining list of elements to iterate over
   * @param folderIDs  a set with the IDs of folder elements
   * @param parentData the aggregated data extracted for parents
   * @tparam ID     the type of IDs of elements
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @tparam DATA   the type for parent data
   */
  private case class DfsCurrentFolder[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](currentID: ID,
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
    def processNextElement(): (Option[ElementWithParentData[ID, DATA]], DfsCurrentFolder[ID, FILE, FOLDER, DATA]) =
      elements match {
        case h :: t => (Some(ElementWithParentData(h, parentData)), copy(elements = t))
        case Nil => (None, this)
      }
  }

  /**
   * A data class holding the state of an iteration in DFS order.
   *
   * @param activeFolders   the folders that are currently processed
   * @param resolvedFolders a map with the folders whose content has already
   *                        been resolved
   * @tparam ID     the type of IDs of elements
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   * @tparam DATA   the type for parent data
   */
  private case class DfsState[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](activeFolders: List[DfsCurrentFolder[ID, FILE, FOLDER, DATA]],
                                      resolvedFolders: Map[ID, Model.FolderContent[ID, FILE, FOLDER]])

  /**
   * Implementation of a [[Source]] that produces the data of a walk operation
   * with the help of a [[WalkFunc]] that determines the order in which the
   * folder structure is processed.
   *
   * @param fileSystem the [[FileSystem]] that is the target of the iteration
   * @param httpActor  the actor for sending HTTP requests
   * @param rootID     the ID of the root folder of the iteration
   * @param transform  the transformer function
   * @param parentData the function to extract parent data
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type for parent data
   */
  private abstract class WalkSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                      httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                      rootID: ID,
                                      transform: TransformFunc[ID],
                                      parentData: ParentDataFunc[ID, DATA])
                                     (implicit system: ActorSystem[_])
    extends GraphStage[SourceShape[ElementWithParentData[ID, DATA]]] {
    /** Type definition for the specific state of the iteration. */
    type WalkState

    private val out: Outlet[ElementWithParentData[ID, DATA]] = Outlet("WalkSource")

    override def shape: SourceShape[ElementWithParentData[ID, DATA]] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        /** Callback for the asynchronous operation to load folder contents. */
        private val resolvedFoldersCallback = getAsyncCallback(onFoldersResolved)

        /** The current state of the iteration. */
        private var currentState: WalkState = _

        /**
         * Stores the ID of folders for which the content has to be fetched.
         * This is done in chunks.
         */
        private var foldersToResolve = Vector(rootID)

        /**
         * Stores already resolved folder content objects. They are passed to
         * the iteration function the next time data is to be fetched.
         */
        private var resolvedFolders = Vector.empty[Model.FolderContent[ID, FILE, FOLDER]]

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
          if (!resolveInProgress && foldersToResolve.nonEmpty) {
            implicit val ec: ExecutionContext = system.executionContext
            Future.sequence(foldersToResolve.take(ResolveFoldersChunkSize)
              .map { folderID =>
                val operation = fileSystem.folderContent(folderID)
                operation.run(httpActor)
              }).onComplete(resolvedFoldersCallback.invoke)
            resolveInProgress = true
            foldersToResolve = foldersToResolve.drop(ResolveFoldersChunkSize)
            true
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
                foldersToResolve = folderIDs.toVector
              } else {
                resolvedFolders = resolvedFolders :++ contents
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
            val actionTaken = walkFunc(currentState, resolvedFolders, transform, parentData) match {
              case WalkProceed(nextState, optData, folderIDs) =>
                currentState = nextState
                foldersToResolve = foldersToResolve :++ folderIDs
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

            resolvedFolders = Vector.empty
            if (!actionTaken) continueWalking()
          }
        }
      }

    /**
     * Returns the function that controls the iteration. This function is 
     * invoked repeatedly to obtain the elements to pass downstream.
     */
    protected val walkFunc: WalkFunc[ID, FILE, FOLDER, DATA, WalkState]

    /**
     * Initializes the iteration. The base class calls this method after
     * resolving the root folder. A concrete implementation returns the initial
     * walk state and a list with folder IDs to be resolved next.
     *
     * @param rootFolder the root folder for the iteration
     * @return the initial [[WalkState]] and folders to resolve
     */
    protected def initWalk(rootFolder: Model.FolderContent[ID, FILE, FOLDER]): (WalkState, Iterable[ID])
  }

  /**
   * A concrete [[WalkFunc]] for iterating over a folder structure in
   * breadth-first search.
   *
   * @param state          the current walk state
   * @param contents       the contents of resolved folders
   * @param transform      the transformer function
   * @param parentDataFunc the parent data extraction function
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type for parent data
   * @return information to continue the walk operation
   */
  private def walkBfs[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](state: BfsState[ID, FILE, FOLDER, DATA],
                                      contents: Iterable[Model.FolderContent[ID, FILE, FOLDER]],
                                      transform: TransformFunc[ID],
                                      parentDataFunc: ParentDataFunc[ID, DATA]):
  WalkFuncResult[ID, BfsState[ID, FILE, FOLDER, DATA], DATA] = {
    val nextResolvedFolders = state.resolvedFolders ++ contents.map(c => c.folderID -> c)

    state.currentFolderElements match {
      case h :: t =>
        val nextState = state.copy(currentFolderElements = t, resolvedFolders = nextResolvedFolders)
        WalkProceed(nextState, Some(h), Nil)

      case _ =>
        state.foldersToProcess.headOption match {
          case Some((folderID, parentData)) if nextResolvedFolders.contains(folderID) =>
            val folder = nextResolvedFolders(folderID)
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
              foldersToProcess = nextQueue :++ subFoldersToProcess,
              resolvedFolders = nextResolvedFolders - folderID
            )
            WalkProceed(nextState, folderElements.headOption, subFoldersToProcess.map(_._1))

          case Some(_) =>
            WalkFoldersPending

          case None =>
            WalkComplete
        }
    }
  }

  /**
   * A concrete [[WalkFunc]] for iterating over a folder structure in
   * depth-first search.
   *
   * @param state          the current walk state
   * @param contents       the contents of resolved folders
   * @param transform      the transformer function
   * @param parentDataFunc the parent data extraction function
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @tparam DATA   the type for parent data
   * @return information to continue the walk operation
   */
  private def walkDfs[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID], DATA](state: DfsState[ID, FILE, FOLDER, DATA],
                                      contents: Iterable[Model.FolderContent[ID, FILE, FOLDER]],
                                      transform: TransformFunc[ID],
                                      parentDataFunc: ParentDataFunc[ID, DATA]):
  WalkFuncResult[ID, DfsState[ID, FILE, FOLDER, DATA], DATA] = {
    val nextResolvedFolders = state.resolvedFolders ++ contents.map(c => c.folderID -> c)

    state.activeFolders match {
      case current :: nextElements =>
        val (optNext, updatedCurrent) = current.processNextElement()
        optNext match {
          case optElem@Some(element) if !current.isFolder(element.element) =>
            val nextState = state.copy(
              activeFolders = updatedCurrent :: nextElements,
              resolvedFolders = nextResolvedFolders
            )
            WalkProceed(nextState, optElem, Nil)

          case optElem@Some(element) if nextResolvedFolders.contains(element.element.id) =>
            val (nextCurrent, optFirstFolderID) =
              prepareFolderForDfs(nextResolvedFolders(element.element.id), Some(element), transform, parentDataFunc)
            val nextState = state.copy(
              activeFolders = nextCurrent :: updatedCurrent :: nextElements,
              resolvedFolders = nextResolvedFolders
            )
            val foldersToResolve = List(optFirstFolderID, updatedCurrent.nextFolderID).flatten
            WalkProceed(nextState, optElem, foldersToResolve)

          case Some(_) =>
            WalkFoldersPending

          case None =>
            val nextState = state.copy(
              activeFolders = nextElements,
              resolvedFolders = nextResolvedFolders - current.currentID
            )
            WalkProceed(nextState, None, Nil)
        }

      case Nil =>
        WalkComplete
    }
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
  (DfsCurrentFolder[ID, FILE, FOLDER, DATA], Option[ID]) = {
    val nextParentData = optParentElement.map { parentElement =>
      val parentFolder = parentElement.element.asInstanceOf[Model.Folder[ID]]
      parentDataFunc(parentFolder).fold(parentElement.parentData) { data =>
        data :: parentElement.parentData
      }
    }.getOrElse(List.empty)

    val nextFolder = DfsCurrentFolder[ID, FILE, FOLDER, DATA](
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
