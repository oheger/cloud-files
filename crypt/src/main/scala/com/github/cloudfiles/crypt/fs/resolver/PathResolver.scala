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

import akka.actor.typed.ActorSystem
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.crypt.fs.CryptConfig

/**
 * A trait defining a component that can resolve paths on a file system with
 * encrypted file and folder names.
 *
 * In such a file system, resolving a path can become an expensive operation.
 * Depending on the cryptographic algorithm in use, it may not be sufficient to
 * just encrypt the path; if every encrypt operation yields different output,
 * this does not help. It is then necessary to traverse the path to be resolved
 * and find a match for all components.
 *
 * This trait defines a generic signature for a resolve operation. There can be
 * multiple implementations with different characteristics that support
 * concrete use cases better or worse.
 *
 * If a concrete ''PathResolver'' implementation requires some state to be
 * managed, e.g. when a kind of cache is used, it may be necessary to free
 * resources when the resolver is no longer needed. To make this possible, this
 * trait extends ''AutoClosable''. It also provides an empty default
 * implementation of the ''close()'' method.
 *
 * @tparam ID     the type of IDs used by the file system
 * @tparam FILE   the type of files
 * @tparam FOLDER the type of folders
 */
trait PathResolver[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]] extends AutoCloseable {
  /**
   * Resolves a path (specified as a sequence of path components) against a
   * file system. If this operation is successful, a ''Future'' with the ID of
   * the element referenced by the path is returned.
   *
   * @param components  the sequence of path components to resolve
   * @param fs          the ''FileSystem'' in which to resolve the path
   * @param cryptConfig the cryptography-related configuration
   * @param system      the actor system
   * @return an ''Operation'' with the result of the resolve operation
   */
  def resolve(components: Seq[String], fs: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
              cryptConfig: CryptConfig)(implicit system: ActorSystem[_]): Operation[ID]

  /**
   * Frees up resources used by this object when it is no longer needed. This
   * base implementation does nothing.
   */
  override def close(): Unit = {}
}
