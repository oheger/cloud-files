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

package com.github.cloudfiles.crypt.service

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.crypt.alg.{CryptAlgorithm, CryptCipher}

import java.security.{Key, SecureRandom}
import java.util.Base64

/**
 * A module offering functionality related to cryptographic operations.
 *
 * The functions provided by this service simplify the usage of a
 * [[CryptStage]] to encrypt or decrypt data obtained from different sources.
 */
object CryptService {
  /**
   * Encodes the given data to a Base64 string using URL-safe encoding. A
   * Base64 encoding is a good way to represent encrypted text as strings.
   *
   * @param data the data to be encoded
   * @return the encoded data
   */
  def encodeBase64(data: ByteString): String =
    Base64.getUrlEncoder.encodeToString(data.toArray)

  /**
   * Decodes the given Base64 string to its original byte chunk representation.
   * Throws an ''IllegalArgumentException'' exception if the input String
   * contain unexpected characters.
   *
   * @param textBase64 the Base64-encoded string
   * @return the decoded data
   */
  def decodeBase64(textBase64: String): ByteString =
    ByteString(Base64.getUrlDecoder.decode(textBase64))

  /**
   * Encrypts the given string using a specific algorithm and key.
   *
   * @param algorithm the ''CryptAlgorithm''
   * @param key       the key for encryption
   * @param text      the input string
   * @param secRandom the random object
   * @return the result of the encryption
   */
  def encryptText(algorithm: CryptAlgorithm, key: Key, text: String)
                 (implicit secRandom: SecureRandom): ByteString =
    transformData(algorithm.encryptCipher(key), ByteString(text))

  /**
   * Encrypts the given string using a specific algorithm and key and encodes
   * the result to Base64. This is a convenience function that combines the
   * encrypt with the encode operation.
   *
   * @param algorithm the ''CryptAlgorithm''
   * @param key       the key for encryption
   * @param text      the input string
   * @param secRandom the random object
   * @return the Base64-encoded encrypted string
   */
  def encryptTextToBase64(algorithm: CryptAlgorithm, key: Key, text: String)
                         (implicit secRandom: SecureRandom): String =
    encodeBase64(encryptText(algorithm, key, text))

  /**
   * Returns a source derived from the provided one, but with encryption added
   * using the specified algorithm and key. This function basically adds a
   * [[CryptStage]] with a proper configuration to the source.
   *
   * @param algorithm the ''CryptAlgorithm''
   * @param key       the key for encryption
   * @param source    the source to encrypt
   * @param secRandom the random object
   * @tparam Mat the type of the data materialized by the source
   * @return the source applying an encryption transformation
   */
  def encryptSource[Mat](algorithm: CryptAlgorithm, key: Key, source: Source[ByteString, Mat])
                        (implicit secRandom: SecureRandom): Source[ByteString, Mat] =
    transformSource(algorithm.encryptCipher(key), source)

  /**
   * Decrypts the given block of data using a specific algorithm and key.
   *
   * @param algorithm the ''CryptAlgorithm''
   * @param key       the key for decryption
   * @param text      the text to be decrypted as ''ByteString''
   * @param secRandom the random object
   * @return the result of the decryption
   */
  def decryptText(algorithm: CryptAlgorithm, key: Key, text: ByteString)
                 (implicit secRandom: SecureRandom): String =
    transformData(algorithm.decryptCipher(key), text).utf8String

  /**
   * Decrypts the given Base64-encoded input data using a specific algorithm
   * and key. The input string must contain only expected characters;
   * otherwise, an ''IllegalArgumentException'' exception is thrown. This is a
   * convenience function that combined the decode with the decrypt operation.
   *
   * @param algorithm    the ''CryptAlgorithm''
   * @param key          the key for decryption
   * @param textBase64   the Base64-encoded input string
   * @param secureRandom the random object
   * @return the resulting decrypted string
   */
  def decryptTextFromBase64(algorithm: CryptAlgorithm, key: Key, textBase64: String)
                           (implicit secureRandom: SecureRandom): String =
    decryptText(algorithm, key, decodeBase64(textBase64))

  /**
   * Returns a source derived from the provided one, but with decryption added
   * using the specified algorithm and key. This function basically adds a
   * [[CryptStage]] with a proper configuration to the source.
   *
   * @param algorithm the ''CryptAlgorithm''
   * @param key       the key for decryption
   * @param source    the source to decrypt
   * @param secRandom the random object
   * @tparam Mat the type of the data materialized by the source
   * @return the source applying the decryption transformation
   */
  def decryptSource[Mat](algorithm: CryptAlgorithm, key: Key, source: Source[ByteString, Mat])
                        (implicit secRandom: SecureRandom): Source[ByteString, Mat] =
    transformSource(algorithm.decryptCipher(key), source)

  /**
   * Applies a crypt operation represented by the cipher provided to the given
   * input data.
   *
   * @param cipher    the ''CryptCipher''
   * @param input     the input data to be transformed
   * @param secRandom the random object
   * @return the result of the transformation
   */
  private def transformData(cipher: CryptCipher, input: ByteString)(implicit secRandom: SecureRandom): ByteString =
    cipher.init(secRandom, input) ++ cipher.complete()

  /**
   * Decorates the given source with a [[CryptStage]] that is configured with
   * the ''CryptCipher'' provided.
   *
   * @param cipher    the ''CryptCipher''
   * @param source    the source to transform
   * @param secRandom the random object
   * @tparam Mat the type of the data materialized by the source
   * @return the transformed source
   */
  private def transformSource[Mat](cipher: CryptCipher, source: Source[ByteString, Mat])
                                  (implicit secRandom: SecureRandom): Source[ByteString, Mat] =
    source.via(new CryptStage(cipher))
}
