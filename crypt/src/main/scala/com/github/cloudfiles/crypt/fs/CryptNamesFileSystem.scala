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

package com.github.cloudfiles.crypt.fs

import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.{DelegateFileSystem, ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.crypt.alg.CryptAlgorithm
import com.github.cloudfiles.crypt.service.CryptService

import java.security.{Key, SecureRandom}
import scala.concurrent.Future

object CryptNamesFileSystem {
  /**
   * Constructs an ''ElementPatchSpec'' to patch the name of an element.
   *
   * @param name the new name
   * @return the ''ElementPatchSpec'' to patch this name
   */
  private def patchSpec(name: String): ElementPatchSpec =
    ElementPatchSpec(patchName = Some(name))
}

/**
 * A file system extension that automatically encrypts the names of files and
 * folders stored in an underlying file system.
 *
 * When new files or folders are created, their names are encrypted using the
 * configured [[CryptAlgorithm]] and encryption key. Analogously, when files
 * or folders are resolved, or the content of a folder is requested, the names
 * are again decrypted. When used together with [[CryptContentFileSystem]] both
 * the names and content of files gets encrypted, which is a rather secure
 * storage form.
 *
 * This implementation assumes that encrypting a string twice does not
 * necessarily yield the same result. This makes the mechanism to resolve a
 * path harder because the single path components cannot be simply encrypted.
 * Rather, starting from the file system root, the contents of folders have to
 * be obtained, decrypted, and searched for the current path component. If the
 * component can be resolved, the same operation has to be done for the next
 * path component until all components have been resolved.
 *
 * @param delegate   the underlying file system
 * @param algorithm  the ''CryptAlgorithm'' to be used
 * @param keyEncrypt the key for encryption
 * @param keyDecrypt the key for decryption
 * @param secRandom  the random object
 * @tparam ID     the type of element IDs
 * @tparam FILE   the type to represent a file
 * @tparam FOLDER the type to represent a folder
 */
class CryptNamesFileSystem[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]
(override val delegate: ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
 algorithm: CryptAlgorithm,
 keyEncrypt: Key,
 keyDecrypt: Key)
(implicit secRandom: SecureRandom)
  extends DelegateFileSystem[ID, FILE, FOLDER] {

  import CryptNamesFileSystem._

  /**
   * @inheritdoc This implementation obtains the folder to be resolved from the
   *             underlying file system and then decrypts its name.
   */
  override def resolveFolder(id: ID)(implicit system: ActorSystem[_]): Operation[FOLDER] =
    super.resolveFolder(id) map folderWithDecryptedName

  /**
   * @inheritdoc This implementation obtains the file to be resolved from the
   *             underlying file system and then decrypts its name.
   */
  override def resolveFile(id: ID)(implicit system: ActorSystem[_]): Operation[FILE] =
    super.resolveFile(id) map fileWithDecryptedName

  /**
   * @inheritdoc This implementation encrypts the name of the folder before
   *             delegating to the underlying file system.
   */
  override def createFolder(parent: ID, folder: Model.Folder[ID])(implicit system: ActorSystem[_]): Operation[ID] = {
    val cryptName = encryptElementName(folder)
    val cryptFolder = patchFolder(folder, cryptName)
    delegate.createFolder(parent, cryptFolder)
  }

  /**
   * @inheritdoc This implementation encrypts the name of the file before
   *             delegating to the underlying file system.
   */
  override def createFile(parent: ID, file: Model.File[ID], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[ID] = {
    val cryptName = encryptElementName(file)
    val cryptFile = patchFile(file, cryptName)
    delegate.createFile(parent, cryptFile, content)
  }

  /**
   * @inheritdoc This implementation obtains the content of the selected folder
   *             from the underlying file system. It then returns a new content
   *             object with all element names decrypted.
   */
  override def folderContent(id: ID)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[ID, FILE, FOLDER]] = super.folderContent(id) flatMap { cryptContent =>
    Operation { _ =>
      cryptContent.mapContentParallel(mapFiles = Some(fileWithDecryptedName),
        mapFolders = Some(folderWithDecryptedName))
    }
  }

  /**
   * @inheritdoc This implementation splits the passed in path and delegates to
   *             ''resolvePathComponents()''.
   */
  override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[ID] =
    resolvePathComponents(UriEncodingHelper.splitAndDecodeComponents(path))

  /**
   * @inheritdoc This implementation tries to identify the single components in
   *             the path provided by requesting the root folder content and
   *             the contents of further folders in the sequence of components.
   *             The names in the folder content objects need to be decrypted
   *             first, so that they can be matched against the path
   *             components in the given sequence.
   */
  override def resolvePathComponents(components: Seq[String])(implicit system: ActorSystem[_]): Operation[ID] = {
    val root = delegate.rootID
    if (components.isEmpty) root
    else root flatMap (resolveNextPathComponent(_, components))
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
   * @param system     the actor system
   * @return an ''Operation'' with the ID of the resolved element
   */
  private def resolveNextPathComponent(folderID: ID, components: Seq[String])
                                      (implicit system: ActorSystem[_]): Operation[ID] =
    delegate.folderContent(folderID) flatMap { content =>
      val optId = findPathComponent(content.folders, components.head)
        .orElse(if (components.size == 1) findPathComponent(content.files, components.head) else None)
      optId.fold(failedResolveOperation(components)) { id =>
        if (components.size == 1) Operation(_ => Future.successful(id))
        else resolveNextPathComponent(id, components.tail)
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
   * @tparam A the element type of the map
   * @return an ''Option'' with the ID of the element that was found
   */
  private def findPathComponent[A <: Model.Element[ID]](elements: Map[ID, A], name: String): Option[ID] =
    elements.find(e => decryptElementName(e._2) == name) map (_._1)

  /**
   * Returns the decrypted name of the passed in element.
   *
   * @param elem the element
   * @return the decrypted name of this element
   */
  private def decryptElementName(elem: Model.Element[ID]): String =
    CryptService.decryptTextFromBase64(algorithm, keyDecrypt, elem.name)

  /**
   * Returns the encrypted name of the passed in element.
   *
   * @param elem the element
   * @return the encrypted name of this element
   */
  private def encryptElementName(elem: Model.Element[ID]): String =
    CryptService.encryptTextToBase64(algorithm, keyEncrypt, elem.name)

  /**
   * Invokes the underlying file system to patch the name of a folder.
   *
   * @param orgFolder the folder to patch
   * @param name      the new name of this folder
   * @return the patched folder
   */
  private def patchFolder(orgFolder: Model.Folder[ID], name: String): FOLDER =
    delegate.patchFolder(orgFolder, patchSpec(name))

  /**
   * Invokes the underlying file system to patch the name of a file.
   *
   * @param orgFile the file to patch
   * @param name    the new name of this file
   * @return the patched file
   */
  private def patchFile(orgFile: Model.File[ID], name: String): FILE =
    delegate.patchFile(orgFile, patchSpec(name))

  /**
   * Returns a folder based on the passed in one with the folder name
   * decrypted.
   *
   * @param folder the original folder
   * @return the folder with the decrypted name
   */
  private def folderWithDecryptedName(folder: FOLDER): FOLDER =
    patchFolder(folder, decryptElementName(folder))

  /**
   * Returns a file based on the passed in one with the file name decrypted.
   *
   * @param file the original file
   * @return the file with the decrypted name
   */
  private def fileWithDecryptedName(file: FILE): FILE =
    patchFile(file, decryptElementName(file))
}
