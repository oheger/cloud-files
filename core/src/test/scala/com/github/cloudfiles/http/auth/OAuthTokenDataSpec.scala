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

package com.github.cloudfiles.http.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success}

object OAuthTokenDataSpec {
  /** Test access token. */
  private val AccessToken = "thisIsTheAccessToken"

  /** Test refresh token. */
  private val RefreshToken = "theTokenToRefreshTheAccessToken"

  /** The expected token data instance after parsing. */
  private val ExpectedTokenData = OAuthTokenData(AccessToken, RefreshToken)

  /** Prefix for the token response. */
  private val JsonPrefix =
    """
      |{
      |  "token_type": "bearer",
      |  "expires_in": 3600,
      |  "scope": "someScope"""".stripMargin

  /**
   * Appends a token value to a token response string.
   *
   * @param prefix    the prefix of the string
   * @param tokenType the type of the token
   * @param token     the token value
   * @return the modified token result string
   */
  private def addToken(prefix: String, tokenType: String, token: String): String =
    s"""$prefix,
       |  "$tokenType": "$token"""".stripMargin
}

/**
 * Test class for ''OAuthTokenData''.
 */
class OAuthTokenDataSpec extends AnyFlatSpec with Matchers {

  import OAuthTokenDataSpec._

  "OAuthTokenData" should "parse a valid JSON response with both tokens" in {
    val TokenResponse = addToken(addToken(JsonPrefix, "access_token", AccessToken),
      "refresh_token", RefreshToken) + "}"

    OAuthTokenData.fromJson(TokenResponse, "") match {
      case Failure(exception) =>
        fail("Could not parse response", exception)
      case Success(value) =>
        value should be(ExpectedTokenData)
    }
  }

  it should "parse a valid JSON response with only the access token" in {
    val TokenResponse = addToken(JsonPrefix, "access_token", AccessToken) + "}"

    OAuthTokenData.fromJson(TokenResponse, RefreshToken) match {
      case Failure(exception) =>
        fail("Could not parse response", exception)
      case Success(value) =>
        value should be(ExpectedTokenData)
    }
  }

  it should "parse a JSON response without an access token" in {
    val TokenResponse = addToken(JsonPrefix, "refresh_token", RefreshToken) + "}"

    OAuthTokenData.fromJson(TokenResponse, "*") match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(TokenResponse)
      case r => fail("Unexpected result: " + r)
    }
  }
}
