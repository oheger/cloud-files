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

package com.github.cloudfiles.crypt.alg

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.cloudfiles.core.FileTestHelper

import java.nio.charset.StandardCharsets
import java.security.{Key, SecureRandom}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.Future

/**
 * Basic implementation of the [[CryptAlgorithm]] trait for testing purposes.
 *
 * This algorithm implements a simple shift of characters, so that the plain
 * text "abc" is converted to "bcd".
 *
 * The module also offers some helper functionality that is useful for
 * different test classes.
 */
object ShiftCryptAlgorithm extends CryptAlgorithm {
  /** The name of this algorithm. */
  final val Name = "Shift"

  /** A header added to an encrypted text. */
  private val Header = "Init".getBytes(StandardCharsets.UTF_8)

  /** Marker for the end of a cipher text. */
  private val End = Array(0.toByte)

  /** Constant for the encrypted test text. */
  final val CipherText = encrypt(ByteString(FileTestHelper.TestData))

  /**
   * Returns a key for encrypting data.
   *
   * @return the key to encrypt data
   */
  def encryptKey: Key = generateKey(1)

  /**
   * Returns a key for decrypting data.
   *
   * @return the key to decrypt data
   */
  def decryptKey: Key = generateKey(-1)

  override def encryptedSize(orgSize: Long): Long = orgSize + Header.length + End.length

  override def decryptedSize(orgSize: Long): Long = orgSize - Header.length - End.length

  override def encryptCipher(key: Key): CryptCipher = createCipher(key, encrypt = true)

  override def decryptCipher(key: Key): CryptCipher = createCipher(key, encrypt = false)

  /**
   * Convenience function to encrypt a single block of data.
   *
   * @param data the data to encrypt
   * @return the resulting encrypted data
   */
  def encrypt(data: ByteString): ByteString =
    transformBlock(data, encryptCipher(encryptKey))

  /**
   * Convenience function to decrypt a single block of data.
   *
   * @param data the data to decrypt
   * @return the resulting decrypted data
   */
  def decrypt(data: ByteString): ByteString =
    transformBlock(data, encryptCipher(decryptKey))

  /**
   * Returns a sink for concatenating the byte strings that flow through a
   * stream. This is often needed when applying transformations to stream
   * sources.
   *
   * @return the ''Sink'' concatenating the data of a stream
   */
  def byteStringSink: Sink[ByteString, Future[ByteString]] =
    Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)

  /**
   * Runs a stream with the given source and concatenates the resulting data.
   *
   * @param source the source
   * @param mat    the object to materialize the stream
   * @return a future with the bytes received from the source
   */
  def concatStream(source: Source[ByteString, Any])(implicit mat: Materializer): Future[ByteString] =
    source.runWith(byteStringSink)

  /**
   * Generates a key that can be used together with this algorithm.
   *
   * @param shift the number of positions each character is shifted
   * @return the key applying this shift
   */
  private def generateKey(shift: Int): Key = new Key {
    override def getAlgorithm: String = Name

    override def getFormat: String = "standard_shift"

    override def getEncoded: Array[Byte] = Array(shift.toByte)
  }

  /**
   * Creates a ''CryptCipher'' to encrypt or decrypt data.
   *
   * @param key     the key to use for the crypt operation
   * @param encrypt flag for encryption
   * @return the ''CryptCipher'' instance
   */
  private def createCipher(key: Key, encrypt: Boolean): CryptCipher = new CryptCipher {
    val shift = new AtomicInteger

    val foundEnd = new AtomicBoolean

    override def init(secRandom: SecureRandom, initChunk: ByteString): ByteString = {
      assert(key.getAlgorithm == Name, "Unsupported key")
      assert(secRandom != null, "No random source provided")
      shift.set(key.getEncoded.apply(0))

      if (encrypt) {
        ByteString(Header) ++ transform(initChunk)
      } else {
        assert(initChunk.startsWith(Header), "Expected header not found")
        transform(initChunk.drop(Header.length))
      }
    }

    override def transform(chunk: ByteString): ByteString = {
      val chunkToTransform = if (!encrypt && chunk.endsWith(End)) {
        foundEnd.set(true)
        chunk.dropRight(End.length)
      } else chunk
      chunkToTransform.map(b => (b + shift.get()).toByte)
    }

    override def complete(): ByteString =
      if (encrypt) ByteString(End)
      else {
        assert(foundEnd.get(), "End marker not found")
        ByteString.empty
      }
  }

  /**
   * Transforms data as a whole using this pseudo algorithm.
   *
   * @param data   the data to transform
   * @param cipher the cipher
   * @return the result of the transformation
   */
  private def transformBlock(data: ByteString, cipher: CryptCipher): ByteString =
    cipher.init(new SecureRandom, data) ++ cipher.complete()
}
