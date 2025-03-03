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
}
