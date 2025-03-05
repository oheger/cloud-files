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
   * Returns a [[Source]] to iterate over a folder structure in breadth-first
   * search order.
   *
   * @param fileSystem the [[FileSystem]] that is the target of the iteration
   * @param httpActor  the actor for sending HTTP requests
   * @param rootID     the ID of the root folder of the iteration
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return the [[Source]] with the encountered elements
   */
  def bfsSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                rootID: ID)
                               (implicit system: ActorSystem[_]): Source[Model.Element[ID], NotUsed] = {
    val walkSource = new WalkSource(fileSystem, httpActor, rootID) {
      override type WalkState = BfsState[ID, FILE, FOLDER]

      override protected val walkFunc: WalkFunc[ID, FILE, FOLDER, WalkState] = walkBfs

      override protected def initialState: WalkState = BfsState(List.empty, Queue.empty)
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
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return the [[Source]] with the encountered elements
   */
  def dfsSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                rootID: ID)
                               (implicit system: ActorSystem[_]): Source[Model.Element[ID], NotUsed] = {
    val walkSource = new WalkSource(fileSystem, httpActor, rootID) {
      override type WalkState = DfsState[ID, FILE, FOLDER]

      override protected val walkFunc: WalkFunc[ID, FILE, FOLDER, WalkState] = walkDfs

      override protected def initialState: WalkState = DfsState(List.empty, Map.empty)
    }

    Source.fromGraph(walkSource)
  }

  /**
   * Definition of a function that controls the iteration over the folder 
   * structure. The function operates on a specific state that is managed by 
   * the source implementation. It is passed such a state object and a list
   * with [[Model.FolderContent]] objects that have been resolved from the
   * file system. It returns an [[Option]] that is ''None'' at the end of the
   * iteration. Otherwise, it contains a tuple with the updated state, an
   * [[Option]] with the next element to pass downstream, and a list with the
   * IDs of folders whose content is needed.
   */
  private type WalkFunc[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID], STATE] =
    (STATE, Iterable[Model.FolderContent[ID, FILE, FOLDER]]) => Option[(STATE, Option[Model.Element[ID]], List[ID])]

  /**
   * A data class holding the state of an iteration in BFS order.
   *
   * @param currentFolderElements the elements from the current
   * @param foldersToProcess      the content of folders to be processed next
   * @tparam ID     the type of IDs of elements
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class BfsState[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](currentFolderElements: List[Model.Element[ID]],
                                foldersToProcess: Queue[Model.FolderContent[ID, FILE, FOLDER]])

  /**
   * A data class to store information about the currently processed folder in
   * depth-first search iteration order.
   *
   * @param currentID the ID of the current folder
   * @param elements  the remaining list of elements to iterate over
   * @param folderIDs a set with the IDs of folder elements
   * @tparam ID     the type of IDs of elements
   * @tparam FILE   the type of files
   * @tparam FOLDER the type of folders
   */
  private case class DfsCurrentFolder[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](currentID: ID,
                                elements: List[Model.Element[ID]],
                                folderIDs: Set[ID]) {
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
    def processNextElement(): (Option[Model.Element[ID]], DfsCurrentFolder[ID, FILE, FOLDER]) =
      elements match {
        case h :: t => (Some(h), copy(elements = t))
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
   */
  private case class DfsState[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](activeFolders: List[DfsCurrentFolder[ID, FILE, FOLDER]],
                                resolvedFolders: Map[ID, Model.FolderContent[ID, FILE, FOLDER]])

  /**
   * Implementation of a [[Source]] that produces the data of a walk operation
   * with the help of a [[WalkFunc]] that determines the order in which the
   * folder structure is processed.
   *
   * @param fileSystem the [[FileSystem]] that is the target of the iteration
   * @param httpActor  the actor for sending HTTP requests
   * @param rootID     the ID of the root folder of the iteration
   * @param system     the actor system
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   */
  private abstract class WalkSource[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                rootID: ID)
                               (implicit system: ActorSystem[_])
    extends GraphStage[SourceShape[Model.Element[ID]]] {
    /** Type definition for the specific state of the iteration. */
    type WalkState

    private val out: Outlet[Model.Element[ID]] = Outlet("WalkSource")

    override def shape: SourceShape[Model.Element[ID]] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        /** Callback for the asynchronous operation to load folder contents. */
        private val resolvedFoldersCallback = getAsyncCallback(onFoldersResolved)

        /** The current state of the iteration. */
        private var currentState = initialState

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
          if (foldersToResolve.nonEmpty) {
            implicit val ec: ExecutionContext = system.executionContext
            Future.sequence(foldersToResolve.take(ResolveFoldersChunkSize)
              .map { folderID =>
                val operation = fileSystem.folderContent(folderID)
                operation.run(httpActor)
              }).foreach(resolvedFoldersCallback.invoke)
            resolveInProgress = true
            foldersToResolve = foldersToResolve.drop(ResolveFoldersChunkSize)
            true
          } else false
        }

        /**
         * Handles a result of a resolve operation on folders. Stores the
         * folder contents and continues with the iteration if possible.
         *
         * @param contents the resolved folder contents
         */
        private def onFoldersResolved(contents: Vector[Model.FolderContent[ID, FILE, FOLDER]]): Unit = {
          resolveInProgress = false
          resolvedFolders = contents
          continueWalking()
        }

        /**
         * Checks whether currently data is available that can be pushed
         * downstream by invoking the walk function. All necessary steps are
         * done to continue with the iteration.
         */
        @tailrec private def continueWalking(): Unit = {
          if (pulled && !resolveInProgress) {
            val actionTaken = walkFunc(currentState, resolvedFolders) match {
              case Some((nextState, optData, folderIDs)) =>
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

              case None =>
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
    protected val walkFunc: WalkFunc[ID, FILE, FOLDER, WalkState]

    /**
     * Returns the initial state for the iteration.
     *
     * @return the initial [[WalkState]]
     */
    protected def initialState: WalkState
  }

  /**
   * A concrete [[WalkFunc]] for iterating over a folder structure in
   * breadth-first search.
   *
   * @param state    the current walk state
   * @param contents the contents of resolved folders
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return information to continue the walk operation
   */
  private def walkBfs[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](state: BfsState[ID, FILE, FOLDER],
                                contents: Iterable[Model.FolderContent[ID, FILE, FOLDER]]):
  Option[(BfsState[ID, FILE, FOLDER], Option[Model.Element[ID]], List[ID])] = {
    val nextFolders = state.foldersToProcess.appendedAll(contents)

    state.currentFolderElements match {
      case h :: t =>
        Some((BfsState(t, nextFolders), Some(h), Nil))

      case _ =>
        nextFolders.dequeueOption match {
          case Some((folder, nextQueue)) =>
            val subFolders = folder.folders.values.toList
            val subFiles = folder.files.values.toList
            val subFilesTail = if (subFiles.isEmpty) Nil else subFiles.tail
            val nextElements = subFilesTail ::: subFolders
            val nextState = BfsState(nextElements, nextQueue)
            Some((nextState, subFiles.headOption, subFolders.map(_.id)))
          case None =>
            None
        }
    }
  }

  /**
   * A concrete [[WalkFunc]] for iterating over a folder structure in
   * depth-first search.
   *
   * @param state    the current walk state
   * @param contents the contents of resolved folders
   * @tparam ID     the type of element IDs
   * @tparam FILE   the type for files
   * @tparam FOLDER the type for folders
   * @return information to continue the walk operation
   */
  private def walkDfs[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](state: DfsState[ID, FILE, FOLDER],
                                contents: Iterable[Model.FolderContent[ID, FILE, FOLDER]]):
  Option[(DfsState[ID, FILE, FOLDER], Option[Model.Element[ID]], List[ID])] = {
    val nextResolvedFolders = state.resolvedFolders ++ contents.map(c => c.folderID -> c)

    state.activeFolders match {
      case current :: nextElements =>
        val (optNext, updatedCurrent) = current.processNextElement()
        optNext match {
          case optElem@Some(element) if current.isFolder(element) =>
            val (nextCurrent, optFirstFolderID) = prepareFolderForDfs(nextResolvedFolders(element.id))
            val nextState = state.copy(
              activeFolders = nextCurrent :: updatedCurrent :: nextElements,
              resolvedFolders = nextResolvedFolders
            )
            val foldersToResolve = List(optFirstFolderID, updatedCurrent.nextFolderID).flatten
            Some(nextState, optElem, foldersToResolve)

          case optElem@Some(_) =>
            val nextState = state.copy(
              activeFolders = updatedCurrent :: nextElements,
              resolvedFolders = nextResolvedFolders
            )
            Some(nextState, optElem, Nil)

          case None =>
            val nextState = state.copy(
              activeFolders = nextElements,
              resolvedFolders = nextResolvedFolders - current.currentID
            )
            Some(nextState, None, Nil)
        }

      case Nil if nextResolvedFolders.nonEmpty =>
        // This is the initial state.
        val (nextCurrent, optFirstFolderID) = prepareFolderForDfs(nextResolvedFolders.values.head)
        val nextState = state.copy(
          activeFolders = List(nextCurrent),
          resolvedFolders = nextResolvedFolders
        )
        val foldersToResolve = optFirstFolderID.fold(List.empty[ID])(f => List(f))
        Some(nextState, None, foldersToResolve)

      case Nil =>
        None
    }
  }

  /**
   * Creates a new [[DfsCurrentFolder]] object for the given folder to continue
   * the iteration with this element. Also, returns an [[Option]] with the
   * first folder ID in the list of elements in this folder, which has to be
   * resolved next.
   *
   * @param content the content of the next current folder
   * @return a tuple with the new current DFS folder and an optional ID of a
   *         folder whose content should be retrieved
   */
  private def prepareFolderForDfs[ID, FILE <: Model.File[ID],
    FOLDER <: Model.Folder[ID]](content: Model.FolderContent[ID, FILE, FOLDER]):
  (DfsCurrentFolder[ID, FILE, FOLDER], Option[ID]) = {
    val nextFolder = DfsCurrentFolder[ID, FILE, FOLDER](
      content.folderID,
      content.files.values.toList ::: content.folders.values.toList,
      content.folders.keySet
    )
    (nextFolder, nextFolder.nextFolderID)
  }
}
