/*
 * Copyright 2020-2022 The Developers Team.
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

import akka.actor.typed.ActorSystem
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.crypt.fs.CryptConfig
import com.github.cloudfiles.crypt.service.CryptService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * A specialized ''PathResolver'' implementation that resolves paths directly
 * by matching the single components against the decrypted path names on each
 * level.
 *
 * This is the most basic resolver to be used if the single path components
 * cannot simply be encrypted (because encryption involves a random initial
 * vector, so that each encrypt operation yields different results). The
 * implementation navigates through the single path components. For each
 * component, the folder content is requested, and the names of its elements
 * are decrypted, until a match is found. This is a rather expensive operation.
 *
 * This implementation is appropriate if only few resolve operations are done,
 * and the client mainly uses IDs to access elements directly. In this case,
 * for the few resolve operations there is no benefit in spending additional
 * effort in caching or other optimizations.
 *
 * @tparam ID     the type of IDs used by the file system
 * @tparam FILE   the type of files
 * @tparam FOLDER the type of folders
 */
class PathComponentsResolver[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]
  extends PathResolver[ID, FILE, FOLDER] {
  override def resolve(components: Seq[String],
                       fs: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                       cryptConfig: CryptConfig)
                      (implicit system: ActorSystem[_]): Operation[ID] = {
    implicit val ec: ExecutionContext = system.executionContext
    val root = fs.rootID
    if (components.isEmpty) root
    else root flatMap (resolveNextPathComponent(_, components, fs, cryptConfig))
  }

  /**
   * Tries to resolve a path component in the content of the folder with the
   * given ID. This function is invoked recursively until all path components
   * have been resolved or an unresolvable component is encountered. In the
   * latter case, the resulting ''Future'' fails with an
   * ''IllegalArgumentException'' exception whose message lists the components
   * that could not be resolved.
   *
   * @param folderID   the ID of the current folder
   * @param components the remaining components to be resolved
   * @param fs         the file system
   * @param config     the cryptography-related configuration
   * @param system     the actor system
   * @return an ''Operation'' with the ID of the resolved element
   */
  private def resolveNextPathComponent(folderID: ID, components: Seq[String],
                                       fs: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                       config: CryptConfig)
                                      (implicit system: ActorSystem[_]): Operation[ID] = {
    implicit val ec: ExecutionContext = system.executionContext
    fs.folderContent(folderID) flatMap { content =>
      val optId = findPathComponent(content.folders, components.head, config)
        .orElse(if (components.size == 1) findPathComponent(content.files, components.head, config) else None)
      optId.fold(failedResolveOperation(components)) { id =>
        if (components.size == 1) Operation(_ => Future.successful(id))
        else resolveNextPathComponent(id, components.tail, fs, config)
      }
    }
  }

  /**
   * Returns an ''Operation'' to report a failure when resolving path
   * components.
   *
   * @param components the components that could not be resolved
   * @return the ''Operation'' producing a failed ''Future''
   */
  private def failedResolveOperation(components: Seq[String]): Operation[ID] = Operation { _ =>
    Future.failed(
      new IllegalArgumentException("Could not resolve all path components. Remaining components: " + components))
  }

  /**
   * Looks up an element name in an encrypted map with folder content elements.
   *
   * @param elements the map with encrypted elements
   * @param name     the name to be searched for
   * @param config   the cryptography-related configuration
   * @tparam A the element type of the map
   * @return an ''Option'' with the ID of the element that was found
   */
  private def findPathComponent[A <: Model.Element[ID]](elements: Map[ID, A], name: String, config: CryptConfig):
  Option[ID] =
    elements.find(e => decryptElementName(e._2, config).toOption.contains(name)) map (_._1)

  /**
   * Returns the decrypted name of the passed in element. As this operation can
   * fail for wrongly encoding element names, result is a ''Try''.
   *
   * @param elem   the element
   * @param config the cryptography-related configuration
   * @return a ''Try'' with the decrypted name of this element
   */
  private def decryptElementName(elem: Model.Element[ID], config: CryptConfig): Try[String] =
    CryptService.decryptTextFromBase64(config.algorithm, config.keyDecrypt, elem.name)(config.secRandom)
}
