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

package com.github.cloudfiles.crypt.alg

import java.security.Key

/**
 * A trait representing a concrete algorithm to encrypt and decrypt data.
 *
 * The file systems supporting cryptography make use of this trait to actually
 * encrypt and decrypt data. For this purpose, the trait provides functions to
 * request a [[CryptCipher]] object for a cryptographic operation.
 *
 * For some file system implementations, the size of files must be known before
 * they can be uploaded. To provide this information when encrypting or
 * decrypting the content of files dynamically during upload and download, a
 * concrete algorithm implementation must be able to calculate the resulting
 * size of data that is encrypted or decrypted.
 */
trait CryptAlgorithm {
  /**
   * Calculates the size of data that is going to be encrypted using this
   * algorithm based on its original size.
   *
   * @param orgSize the original size of the data
   * @return the size of this data after encryption
   */
  def encryptedSize(orgSize: Long): Long

  /**
   * Calculates the size of data that is going to be decrypted using this
   * algorithm based on its original (encrypted) size.
   *
   * @param orgSize the original size of the data
   * @return the size of this data after decryption
   */
  def decryptedSize(orgSize: Long): Long

  /**
   * Returns a new ''CryptCipher'' object that can be used to encrypt data. The
   * resulting object is applicable for a single encrypt operation that can
   * involve an arbitrary number of data chunks.
   *
   * @param key the key to be used for encryption
   * @return the ''CryptCipher'' to encrypt data
   */
  def encryptCipher(key: Key): CryptCipher

  /**
   * Returns a new ''CryptCipher'' object that can be used to decrypt data. The
   * resulting object is applicable for a single decrypt operation that can
   * involve an arbitrary number of data chunks.
   *
   * @param key the key to decrypt data
   * @return the ''CryptCipher'' to decrypt data
   */
  def decryptCipher(key: Key): CryptCipher
}
