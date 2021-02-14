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

package com.github.cloudfiles.crypt.alg

import akka.util.ByteString

import java.security.SecureRandom

/**
 * A trait defining a crypto operation that manipulates data (such as
 * encryption or decryption).
 *
 * Implementations of this trait are used to encrypt or decrypt data making use
 * of a concrete algorithm. An instance can be viewed as a representation of a
 * ''Cypher'' in the Java crypto API. The functions provided by this trait are
 * derived from the life-cycle of such an object: it is initialized, chunks of
 * data are passed to it for being transformed, and there is a notification of
 * the end of the sequence to be transformed. The rather stateful nature of
 * this trait is due to the interaction schemes used by the underlying Java
 * API.
 */
trait CryptCipher {
  /**
   * Initializes this object for the current operation. This function is
   * called with the first chunk of data to be processed and some helper
   * objects typically required to correctly initialize a crypt operation.
   *
   * @param secRandom the source of random data
   * @param initChunk the first chunk of data
   * @return the next chunk to be passed downstream
   */
  def init(secRandom: SecureRandom, initChunk: ByteString): ByteString

  /**
   * Transforms the given chunk of data. This function is called repeatedly for
   * all the data chunks to be transformed except for the first one (which is
   * handled by ''init()''. Here the actual encryption/decryption work takes
   * place.
   *
   * @param chunk the current chunk of data to be transformed
   * @return the transformed chunk of data
   */
  def transform(chunk: ByteString): ByteString

  /**
   * Notifies this object that all data has been processed. An implementation
   * can yield a final chunk of data if there are remaining bytes which have
   * not yet been output.
   *
   * @return the final chunk of data
   */
  def complete(): ByteString
}
