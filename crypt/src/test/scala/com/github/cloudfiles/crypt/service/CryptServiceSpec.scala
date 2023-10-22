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
import com.github.cloudfiles.crypt.alg.ShiftCryptAlgorithm.CipherText
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.security.SecureRandom
import scala.util.{Failure, Success}

object CryptServiceSpec {
  /** The source of randomness. */
  private implicit val secRandom: SecureRandom = new SecureRandom
}

class CryptServiceSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with AsyncTestHelper {

  import CryptServiceSpec._

  /**
   * Tests whether the given string has a valid Base64 encoding. This means
   * that the string contains only valid characters.
   *
   * @param base64 the string to be checked
   */
  private def checkBase64Encoding(base64: String): Unit = {
    base64.forall { c =>
      (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '='
    } shouldBe true
  }

  "CryptService" should "provide base64 encoding and decoding" in {
    val SampleData = FileTestHelper.TestData + ",-#*+?!§$%&/(){}_~<>|"
    val sampleData = ByteString(SampleData)

    val base64 = CryptService.encodeBase64(sampleData)
    val decoded = CryptService.decodeBase64(base64)
    decoded should be(Success(sampleData))
    base64 should not be SampleData
    checkBase64Encoding(base64)
  }

  it should "encrypt a text" in {
    val cipherText =
      CryptService.encryptText(ShiftCryptAlgorithm, ShiftCryptAlgorithm.encryptKey, FileTestHelper.TestData)

    cipherText should be(CipherText)
  }

  it should "decrypt data" in {
    val plainText = CryptService.decryptText(ShiftCryptAlgorithm, ShiftCryptAlgorithm.decryptKey, CipherText)

    plainText should be(FileTestHelper.TestData)
  }

  it should "do encryption to and decryption from Base64" in {
    val base64 = CryptService.encryptTextToBase64(ShiftCryptAlgorithm, ShiftCryptAlgorithm.encryptKey,
      FileTestHelper.TestData)
    val plain = CryptService.decryptTextFromBase64(ShiftCryptAlgorithm, ShiftCryptAlgorithm.decryptKey, base64)

    plain should be(Success(FileTestHelper.TestData))
    checkBase64Encoding(base64)
  }

  it should "handle illegal characters when decrypting from Base64" in {
    val InvalidInput = "This is not Base64-encoded?!"

    CryptService.decryptTextFromBase64(ShiftCryptAlgorithm, ShiftCryptAlgorithm.decryptKey, InvalidInput) match {
      case Failure(exception: IOException) =>
        exception.getMessage should include(InvalidInput)
      case res => fail("Unexpected result: " + res)
    }
  }

  /**
   * Runs a stream with the given source and collects the resulting data.
   *
   * @param source the source
   * @return the bytes received from the source
   */
  private def runSource(source: Source[ByteString, Any]): ByteString =
    futureResult(ShiftCryptAlgorithm.concatStream(source))

  it should "encrypt a source" in {
    val source = Source(FileTestHelper.TestData.grouped(32).map(ByteString(_)).toList)
    val cryptSource = CryptService.encryptSource(ShiftCryptAlgorithm, ShiftCryptAlgorithm.encryptKey, source)

    val result = runSource(cryptSource)
    result should be(CipherText)
  }

  it should "decrypt a source" in {
    val source = Source(CipherText.grouped(32).toList)
    val decryptSource = CryptService.decryptSource(ShiftCryptAlgorithm, ShiftCryptAlgorithm.decryptKey, source)

    val result = runSource(decryptSource)
    result.utf8String should be(FileTestHelper.TestData)
  }
}
