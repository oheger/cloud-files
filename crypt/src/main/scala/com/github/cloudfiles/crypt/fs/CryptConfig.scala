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

import com.github.cloudfiles.crypt.alg.CryptAlgorithm

import java.security.{Key, SecureRandom}

/**
 * A data class combining a set of properties that is required for a file
 * system applying cryptographic operations.
 *
 * The properties in this class mainly define the cryptographic algorithm to
 * use and the keys for encryption and decryption.
 *
 * @param algorithm  the cryptographic algorithm
 * @param keyEncrypt the key for encryption
 * @param keyDecrypt the key for decryption
 * @param secRandom  the source for randomness
 */
case class CryptConfig(algorithm: CryptAlgorithm,
                       keyEncrypt: Key,
                       keyDecrypt: Key,
                       secRandom: SecureRandom)
