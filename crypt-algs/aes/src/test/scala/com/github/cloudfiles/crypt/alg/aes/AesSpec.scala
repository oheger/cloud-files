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

package com.github.cloudfiles.crypt.alg.aes

import com.github.cloudfiles.core.FileTestHelper
import com.github.cloudfiles.crypt.alg.CryptCipher
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

object AesSpec {
  /** A default key used for encryption / decryption. */
  private val Key = "0123456789ABCDEF"

  /** The data used by test cases. */
  private val TestData = ByteString(FileTestHelper.TestData)

  /**
   * Performs a complete crypt operation on the cipher provided with the given
   * data. The given data is grouped into chunks, and the cipher methods are
   * invoked for all chunks.
   *
   * @param cipher the ''CryptCipher''
   * @param data   the data to process
   * @return the result of the operation
   */
  private def process(cipher: CryptCipher, data: ByteString): ByteString = {
    val blocks = data.grouped(32).toList
    val initResult = cipher.init(new SecureRandom, blocks.head)
    val result = blocks.drop(1).foldLeft(initResult) { (res, block) =>
      res ++ cipher.transform(block)
    }
    result ++ cipher.complete()
  }
}

/**
 * Test class for ''Aes''.
 */
class AesSpec extends AnyFlatSpec with Matchers {

  import AesSpec._

  /**
   * Executes a round trip of encrypting and decrypting the test data and
   * checks whether the result is the same.
   *
   * @param key  the key to be used
   * @param data the data to be processed
   * @return the encrypted data
   */
  private def checkRoundTrip(key: String, data: ByteString = TestData): ByteString = {
    val cryptKey = Aes.keyFromString(key)
    val encryptCipher = Aes.encryptCipher(cryptKey)
    val decryptCipher = Aes.decryptCipher(cryptKey)

    val encryptedData = process(encryptCipher, data)
    process(decryptCipher, encryptedData) should be(data)
    encryptedData
  }

  "Aes" should "support encrypting and decrypting data" in {
    checkRoundTrip(Key)
  }

  it should "produce encrypted text" in {
    val cryptData = checkRoundTrip(Key)

    cryptData should not be TestData
  }

  it should "produce different cipher text on each crypt operation" in {
    val cryptData1 = checkRoundTrip(Key)

    val cryptData2 = checkRoundTrip(Key)
    cryptData2 should not be cryptData1
  }

  it should "handle small messages as well" in {
    checkRoundTrip(Key, data = ByteString("test"))
  }

  it should "shorten keys that are too long" in {
    checkRoundTrip("This is a very long key! Could cause problems...")
  }

  it should "pad keys that are too short" in {
    checkRoundTrip("foo")
  }

  it should "handle an invalid initial block during decryption" in {
    val decryptCipher = Aes.decryptCipher(Aes.keyFromArray(Key.getBytes(StandardCharsets.UTF_8)))

    intercept[IllegalStateException] {
      decryptCipher.init(new SecureRandom, ByteString("!"))
    }
  }

  it should "correctly calculate the encrypted size" in {
    val cryptData = checkRoundTrip(Key)

    Aes.encryptedSize(TestData.size) should be(cryptData.size)
  }

  it should "correctly calculate the decrypted size" in {
    val cryptData = checkRoundTrip(Key)

    Aes.decryptedSize(cryptData.size) should be(TestData.size)
  }
}
