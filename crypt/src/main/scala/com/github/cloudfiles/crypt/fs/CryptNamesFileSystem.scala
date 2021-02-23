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
    super.resolveFolder(id) map { orgFolder =>
      val decryptName = decryptElementName(orgFolder)
      patchFolder(orgFolder, decryptName)
    }

  /**
   * @inheritdoc This implementation obtains the file to be resolved from the
   *             underlying file system and then decrypts its name.
   */
  override def resolveFile(id: ID)(implicit system: ActorSystem[_]): Operation[FILE] =
    super.resolveFile(id) map { orgFile =>
      val decryptName = decryptElementName(orgFile)
      patchFile(orgFile, decryptName)
    }

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
      val futFiles = decryptFileNames(cryptContent)
      val futFolders = decryptFolderNames(cryptContent)
      for {
        files <- futFiles
        folders <- futFolders
      } yield cryptContent.copy(files = files, folders = folders)
    }
  }

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
   * Decrypts the names of all folders in the given content object and returns
   * a map with patched folder objects.
   *
   * @param content the content object
   * @param system  the actor system
   * @return a future with a map of folders with decrypted names
   */
  private def decryptFolderNames(content: Model.FolderContent[ID, FILE, FOLDER])
                                (implicit system: ActorSystem[_]): Future[Map[ID, FOLDER]] =
    Future.sequence(content.folders.values.map(folder => Future {
      patchFolder(folder, decryptElementName(folder))
    })) map toMap

  /**
   * Decrypts the names of all files in the given content object and returns
   * a map with patched file objects.
   *
   * @param content the content object
   * @param system  the actor system
   * @return a future with a map of files with decrypted names
   */
  private def decryptFileNames(content: Model.FolderContent[ID, FILE, FOLDER])
                              (implicit system: ActorSystem[_]): Future[Map[ID, FILE]] =
    Future.sequence(content.files.values.map(file => Future {
      patchFile(file, decryptElementName(file))
    })) map toMap

  /**
   * Constructs a map from the given elements using the element IDs as keys.
   *
   * @param elements the elements
   * @tparam A the element type
   * @return the map with these elements
   */
  private def toMap[A <: Model.Element[ID]](elements: Iterable[A]): Map[ID, A] =
    elements.map(e => e.id -> e).toMap
}
