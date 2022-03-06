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

package com.github.cloudfiles.core.http.auth

import java.util.regex.Pattern
import scala.util.matching.Regex
import scala.util.{Success, Try}

object OAuthTokenData {
  /** RegEx to extract the access token. */
  private val regAccessToken = jsonPropRegEx("access_token")

  /** RegEx to extract the refresh token. */
  private val regRefreshToken = jsonPropRegEx("refresh_token")

  /**
   * Parses the JSON response of a token request and extracts the token values
   * from it. The response must contain a property for the access token; the
   * refresh token is optional - if it is missing, the passed in refresh token
   * is used instead.
   *
   * @param json                the JSON response string to be parsed
   * @param defaultRefreshToken the default refresh token
   * @return a ''Try'' with the extracted ''OAuthTokenData''
   */
  def fromJson(json: String, defaultRefreshToken: String): Try[OAuthTokenData] = {
    regAccessToken.findFirstMatchIn(json)
      .map { accessToken =>
        val refreshToken = regRefreshToken.findFirstMatchIn(json) map (_.group(1)) getOrElse defaultRefreshToken
        OAuthTokenData(accessToken.group(1), refreshToken)
      }.fold(Try[OAuthTokenData](throw new IllegalArgumentException("Could not parse token response: " +
      json)))(Success(_))
  }

  /**
   * Creates a regular expression that matches the value of the given JSON
   * property.
   *
   * @param property the name of the property
   * @return the regular expression for this property
   */
  private def jsonPropRegEx(property: String): Regex =
    raw""""${Pattern.quote(property)}"\s*:\s*"([^"]+)"""".r
}

/**
 * A data class representing the token material to be stored for a single
 * OAuth client of a specific identity provider.
 *
 * @param accessToken  the access token
 * @param refreshToken the refresh token
 */
case class OAuthTokenData(accessToken: String, refreshToken: String)
