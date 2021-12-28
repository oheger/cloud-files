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
import com.github.cloudfiles.crypt.fs.resolver.{PathComponentsResolver, PathResolver}
import com.github.cloudfiles.crypt.service.CryptService

import java.security.SecureRandom

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
 * For file systems with encrypted names, resolving a path becomes difficult,
 * especially if encrypting a string twice with the algorithm in use does not
 * necessarily yield the same result. To be flexible, such operations are
 * delegated to a [[PathResolver]] object, which can be passed to the
 * constructor. It is then possible to choose a specific resolver
 * implementation that fits the usage scheme of the file system best.
 *
 * @param delegate the underlying file system
 * @param config   the cryptography-related configuration
 * @param resolver the object that handles path resolve operations
 * @tparam ID     the type of element IDs
 * @tparam FILE   the type to represent a file
 * @tparam FOLDER the type to represent a folder
 */
class CryptNamesFileSystem[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]
(override val delegate: ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
 val config: CryptConfig,
 resolver: PathResolver[ID, FILE, FOLDER] = new PathComponentsResolver[ID, FILE, FOLDER])
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
    if (path.isEmpty) delegate.rootID
    else resolvePathComponents(UriEncodingHelper.splitAndDecodeComponents(path))

  /**
   * @inheritdoc This implementation tries to identify the single components in
   *             the path provided by requesting the root folder content and
   *             the contents of further folders in the sequence of components.
   *             The names in the folder content objects need to be decrypted
   *             first, so that they can be matched against the path
   *             components in the given sequence.
   */
  override def resolvePathComponents(components: Seq[String])(implicit system: ActorSystem[_]): Operation[ID] =
    resolver.resolve(components, delegate, config)

  /**
   * @inheritdoc This implementation invokes the ''close()'' function of the
   *             [[PathResolver]] used by this instance.
   */
  override def close(): Unit = {
    resolver.close()
  }

  /**
   * Returns the decrypted name of the passed in element. If this fails -
   * because the element name is not properly encoded -, an ''IOException''
   * with a meaningful message is thrown.
   *
   * @param elem the element
   * @return the decrypted name of this element
   */
  private def decryptElementName(elem: Model.Element[ID]): String =
    CryptService.decryptTextFromBase64(config.algorithm, config.keyDecrypt, elem.name).get

  /**
   * Returns the encrypted name of the passed in element.
   *
   * @param elem the element
   * @return the encrypted name of this element
   */
  private def encryptElementName(elem: Model.Element[ID]): String =
    CryptService.encryptTextToBase64(config.algorithm, config.keyEncrypt, elem.name)

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

  /**
   * Returns the source of randomness in implicit scope.
   *
   * @return the ''SecureRandom'' object
   */
  private implicit def secRandom: SecureRandom = config.secRandom
}
