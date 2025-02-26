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

package com.github.cloudfiles.core.http

object Secret {
  /**
   * Creates a new instance of ''Secret'' from the given string data.
   *
   * @param data the data as string
   * @return the new ''Secret'' instance
   */
  def apply(data: String): Secret = new Secret(data.toCharArray)
}

/**
 * A class representing a secret piece of data, such as a password or an OAuth
 * client secret.
 *
 * The main purpose of this class is to store the secret information in a
 * different representation than a plain string and to provide it as a string
 * on demand.
 *
 * @param secretRaw the secret as character array
 */
class Secret(secretRaw: Array[Char]) {
  /**
   * Returns the managed client secret as string. As the secret is sensitive
   * information, it should not be kept in memory for a longer time as string.
   * Therefore, the string is constructed on demand only. (In general,
   * instances of this class should not be kept in memory for a longer time.)
   *
   * @return the secret as a string
   */
  def secret: String = new String(secretRaw)
}
