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

package com.github.cloudfiles.crypt.service

import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper}
import com.github.cloudfiles.crypt.alg.ShiftCryptAlgorithm
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.FlowShape
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.stage.GraphStage
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.security.SecureRandom

object CryptStageSpec {

  /** Group size for splitting text input for encryption. */
  private val GroupSize = 64

  /** A secure random object in implicit scope. */
  private implicit val random: SecureRandom = new SecureRandom

  /**
   * Splits a text into a number of byte string blocks with a standard chunk
   * size.
   *
   * @param text the input text
   * @return the resulting blocks
   */
  private def splitPlainText(text: String): List[ByteString] =
    text.grouped(GroupSize).map(s => ByteString(s)).toList

  /**
   * Combines a list of binary chunks and returns the result as string.
   *
   * @param data the list of data chunks
   * @return the resulting string
   */
  private def combine(data: List[ByteString]): String =
    data.foldLeft(ByteString.empty)((buf, s) => buf ++ s).utf8String
}

class CryptStageSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with AsyncTestHelper {

  import CryptStageSpec._

  /**
   * Runs a stream that encrypts or decrypts the given data.
   *
   * @param data       the data to be processed
   * @param cryptStage the stage for encryption / decryption
   * @return the list with resulting data chunks
   */
  private def runCryptStream(data: List[ByteString], cryptStage: GraphStage[FlowShape[ByteString, ByteString]]):
  List[ByteString] = {
    val source = Source(data)
    val sink = Sink.fold[List[ByteString], ByteString](Nil)((list, bs) => bs :: list)
    val futResult = source.via(cryptStage)
      .runWith(sink)
    futureResult(futResult).reverse
  }

  /**
   * Runs a stream to encrypt the given message. The message is split into
   * chunks before.
   *
   * @param message the message to be encrypted
   * @return the resulting encrypted chunks
   */
  private def encrypt(message: String): List[ByteString] =
    runCryptStream(splitPlainText(message),
      new CryptStage(ShiftCryptAlgorithm.encryptCipher(ShiftCryptAlgorithm.encryptKey)))

  /**
   * Runs a stream that decrypts the given data chunks and returns the result
   * as string.
   *
   * @param cipherText the chunks of the encrypted message
   * @return the resulting decrypted text
   */
  private def decrypt(cipherText: List[ByteString]): String =
    combine(runCryptStream(cipherText,
      new CryptStage(ShiftCryptAlgorithm.decryptCipher(ShiftCryptAlgorithm.decryptKey))))

  /**
   * Checks an encryption followed by a decryption. This should result in the
   * original message.
   *
   * @param message the message to be processed
   */
  private def checkRoundTrip(message: String): Unit = {
    val encrypted = encrypt(message)
    val processed = decrypt(encrypted)
    processed should be(message)
  }

  "A CryptStage" should "produce the same text when encrypting and decrypting" in {
    checkRoundTrip(FileTestHelper.TestData)
  }

  it should "handle an empty source to encrypt" in {
    val stage = new CryptStage(ShiftCryptAlgorithm.encryptCipher(ShiftCryptAlgorithm.encryptKey))

    runCryptStream(Nil, stage) should have size 0
  }

  it should "handle an empty source to decrypt" in {
    decrypt(Nil) should be("")
  }

  it should "produce encrypted text" in {
    val cipherText = combine(encrypt(FileTestHelper.TestData))

    cipherText should be(ShiftCryptAlgorithm.encrypt(ByteString(FileTestHelper.TestData)).utf8String)
  }

  it should "handle small messages as well" in {
    val Message = "Test"

    checkRoundTrip(Message)
  }
}
