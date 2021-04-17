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
import akka.http.scaladsl.model.HttpEntity
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.{DelegateFileSystem, ElementPatchSpec, ExtensibleFileSystem}
import com.github.cloudfiles.crypt.alg.CryptAlgorithm
import com.github.cloudfiles.crypt.service.CryptService

import java.security.SecureRandom

/**
 * A file system extension that adds support for encrypting the content of
 * files to another file system.
 *
 * When uploading a file to this file system, the content source for the file
 * is transformed to encrypt the data using the configured [[CryptAlgorithm]]
 * and key; so only encrypted data is stored in the underlying file system.
 * Analogously, on download of a file, the source returned by this file system
 * performs decryption transparently.
 *
 * @param delegate the underlying file system
 * @param config   the cryptography-related configuration
 * @tparam ID     the type of element IDs
 * @tparam FILE   the type to represent a file
 * @tparam FOLDER the type to represent a folder
 */
class CryptContentFileSystem[ID, FILE <: Model.File[ID], FOLDER](override val delegate: ExtensibleFileSystem[ID, FILE,
  FOLDER, Model.FolderContent[ID, FILE, FOLDER]], val config: CryptConfig)
  extends DelegateFileSystem[ID, FILE, FOLDER] {
  /**
   * @inheritdoc This implementation makes sure that the correct size of the
   *             encrypted file is returned.
   */
  override def resolveFile(id: ID)(implicit system: ActorSystem[_]): Operation[FILE] =
    super.resolveFile(id) map patchDecryptFileSize

  /**
   * @inheritdoc This implementation patches the size of the files in the
   *             content returned by the underlying file system.
   */
  override def folderContent(id: ID)(implicit system: ActorSystem[_]):
  Operation[Model.FolderContent[ID, FILE, FOLDER]] = super.folderContent(id) flatMap { content =>
    Operation {
      _ => content.mapContentParallel(mapFiles = Some(patchDecryptFileSize))
    }
  }

  /**
   * @inheritdoc This implementation transforms the data source of the entity
   *             from the underlying file system to decrypt the data.
   */
  override def downloadFile(fileID: ID)(implicit system: ActorSystem[_]): Operation[HttpEntity] =
    super.downloadFile(fileID) map { entity =>
      HttpEntity(entity.contentType, CryptService.decryptSource(config.algorithm, config.keyDecrypt, entity.dataBytes))
    }

  /**
   * @inheritdoc This implementation transforms the content source to
   *             automatically encrypt the data of the file. The file size is
   *             adjusted as well.
   */
  override def createFile(parent: ID, file: Model.File[ID], content: Source[ByteString, Any])
                         (implicit system: ActorSystem[_]): Operation[ID] = {
    val patchedFile = patchEncryptFileSize(file)
    val cryptSource = CryptService.encryptSource(config.algorithm, config.keyEncrypt, content)
    super.createFile(parent, patchedFile, cryptSource)
  }

  /**
   * @inheritdoc This implementation transforms the content source to
   *             automatically encrypt the data of the file. The file size is
   *             adjusted as well.
   */
  override def updateFileContent(fileID: ID, size: Long, content: Source[ByteString, Any])
                                (implicit system: ActorSystem[_]): Operation[Unit] = {
    val cryptSize = config.algorithm.encryptedSize(size)
    val cryptSource = CryptService.encryptSource(config.algorithm, config.keyEncrypt, content)
    super.updateFileContent(fileID, cryptSize, cryptSource)
  }

  /**
   * Patches the size of the given file to the correct size of the decrypted
   * file content.
   *
   * @param file the file to patch
   * @return the file with the patched size
   */
  private def patchDecryptFileSize(file: FILE): FILE =
    patchFileSize(file, config.algorithm.decryptedSize(file.size))

  /**
   * Patches the size of the given file to the correct size of the encrypted
   * file content.
   *
   * @param file the file to patch
   * @return the file with the patched size
   */
  private def patchEncryptFileSize(file: Model.File[ID]): FILE =
    patchFileSize(file, config.algorithm.encryptedSize(file.size))

  /**
   * Invokes the delegate file system to created a patched file with the size
   * provided.
   *
   * @param file    the original file
   * @param newSize the new size of this file
   * @return the file with the patched size
   */
  private def patchFileSize(file: Model.File[ID], newSize: Long): FILE =
    delegate.patchFile(file, ElementPatchSpec(patchSize = Some(newSize)))

  /**
   * Returns the source of randomness in implicit scope.
   *
   * @return the ''SecureRandom'' object
   */
  private implicit def secRandom: SecureRandom = config.secRandom
}
