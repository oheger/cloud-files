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

package com.github.cloudfiles.crypt.alg.aes

import com.github.cloudfiles.crypt.alg.{CryptAlgorithm, CryptCipher}
import org.apache.pekko.util.ByteString

import java.nio.charset.StandardCharsets
import java.security.{Key, SecureRandom}
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import scala.annotation.tailrec

/**
 * A module implementing the AES crypto algorithm.
 *
 * This module implements functionality to encrypt and decrypt data using the
 * AES algorithm with Counter (CTR) mode cipher. For each encryption, a random
 * initialization vector is used. This has the effect that encryption of the
 * same plain text multiple times causes different results.
 */
object Aes extends CryptAlgorithm {
  /** The length of the initialization vector. */
  val IvLength = 16

  /** The length of keys required by the encryption algorithm. */
  val KeyLength = 16

  /** The name of the cipher used for encrypt / decrypt operations. */
  val CipherName = "AES/CTR/NoPadding"

  /** The name of the encryption algorithm. */
  val AlgorithmName = "AES"

  /**
   * Generates a key from the given byte array. This method expects that the
   * passed in array has at least the length required by a valid key
   * (additional bytes are ignored).
   *
   * @param keyData the array with the data of the key
   * @return the resulting key
   */
  def keyFromArray(keyData: Array[Byte]): Key =
    new SecretKeySpec(keyData, 0, KeyLength, AlgorithmName)

  /**
   * Generates a key from the given string. If necessary, the string is
   * padded or truncated to come to the correct key length in bytes.
   *
   * @param strKey the string-based key
   * @return the resulting key
   */
  def keyFromString(strKey: String): Key =
    keyFromArray(generateKeyArray(strKey))

  override def encryptedSize(orgSize: Long): Long = orgSize + IvLength

  override def decryptedSize(orgSize: Long): Long = orgSize - IvLength

  override def encryptCipher(key: Key): CryptCipher =
    new AesCryptCipher(createCipher()) {
      override def init(secRandom: SecureRandom, initChunk: ByteString): ByteString = {
        val ivBytes = new Array[Byte](IvLength)
        secRandom.nextBytes(ivBytes)
        val iv = new IvParameterSpec(ivBytes)
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)

        val encData = transform(initChunk)
        ByteString(ivBytes) ++ encData
      }
    }

  override def decryptCipher(key: Key): CryptCipher =
    new AesCryptCipher(createCipher()) {
      override def init(secRandom: SecureRandom, initChunk: ByteString): ByteString = {
        val data = initChunk.toArray
        if (data.length < IvLength)
          throw new IllegalStateException("Illegal initial chunk! The chunk must at least contain the IV.")
        val iv = new IvParameterSpec(data, 0, IvLength)
        cipher.init(Cipher.DECRYPT_MODE, key, iv, secRandom)

        ByteString(cipher.update(data, IvLength, data.length - IvLength))
      }
    }

  /**
   * Transforms the given string key to a byte array which can be used for the
   * creation of a secret key spec. This function takes care that the
   * resulting array has the correct length.
   *
   * @param strKey the string-based key
   * @return an array with key data as base for a secret key spec
   */
  private def generateKeyArray(strKey: String): Array[Byte] = {
    val keyData = strKey.getBytes(StandardCharsets.UTF_8)
    if (keyData.length < KeyLength) {
      val paddedData = new Array[Byte](KeyLength)
      padKeyData(keyData, paddedData, 0)
    } else keyData
  }

  /**
   * Generates key data of the correct length. This is done by repeating the
   * original key data until the desired target length is reached.
   *
   * @param orgData the original (too short) key data
   * @param target  the target array
   * @param idx     the current index
   * @return the array with key data of the desired length
   */
  @tailrec private def padKeyData(orgData: Array[Byte], target: Array[Byte], idx: Int): Array[Byte] =
    if (idx == target.length) target
    else {
      target(idx) = orgData(idx % orgData.length)
      padKeyData(orgData, target, idx + 1)
    }

  /**
   * Creates a ''Cipher'' object to be used for an encrypt or decrypt
   * operation.
   *
   * @return the new ''Cipher'' object
   */
  private def createCipher(): Cipher = Cipher.getInstance(CipherName)

  /**
   * A base class for an AES ''CryptCipher'' implementation that implements the
   * functionality common to encryption and decryption.
   *
   * @param cipher the underlying ''Cipher'' object
   */
  private abstract class AesCryptCipher(val cipher: Cipher) extends CryptCipher {
    override def transform(chunk: ByteString): ByteString =
      ByteString(cipher.update(chunk.toArray))

    override def complete(): ByteString = ByteString(cipher.doFinal())
  }

}
