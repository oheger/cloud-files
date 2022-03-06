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

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthConfig.{IgnoreNotification, TokenRefreshNotificationFunc}

import scala.util.Try

/**
 * A trait representing a configuration for an authentication mechanism.
 *
 * This is just a marker interface. Concrete implementations define their own
 * specific properties.
 */
sealed trait AuthConfig

/**
 * An object representing a dummy configuration for the case that no
 * authentication is needed.
 */
case object NoAuthConfig extends AuthConfig

/**
 * A data class that collects user credentials for accessing a server via basic
 * auth.
 *
 * @param user     the user name
 * @param password the password
 */
case class BasicAuthConfig(user: String, password: Secret) extends AuthConfig

object OAuthConfig {
  /**
   * Definition of a function that is invoked with the outcome of a token
   * refresh operation. Such a function can be passed to an actor instance to
   * react on the availability of new tokens or failures to obtain them.
   */
  type TokenRefreshNotificationFunc = Try[OAuthTokenData] => Unit

  /**
   * A concrete ''TokenRefreshNotificationFunc'' which just ignores all
   * notifications passed to the function.
   */
  final val IgnoreNotification: TokenRefreshNotificationFunc = _ => ()
}

/**
 * A data class collecting the properties required for an OAuth client
 * application.
 *
 * An instance of this class stores the information required for obtaining an
 * OAuth access token based on a refresh token from a specific OAuth2 identity
 * provider.
 *
 * @param tokenEndpoint           the URI of the token endpoint
 * @param redirectUri             the redirect URI
 * @param clientID                the client ID
 * @param clientSecret            the secret to identify the client
 * @param initTokenData           an object with token information (the
 *                                mandatory refresh token and a (possibly
 *                                undefined) access token
 * @param refreshNotificationFunc a function to invoke when tokens are
 *                                refreshed
 */
case class OAuthConfig(tokenEndpoint: String,
                       redirectUri: String,
                       clientID: String,
                       clientSecret: Secret,
                       initTokenData: OAuthTokenData,
                       refreshNotificationFunc: TokenRefreshNotificationFunc = IgnoreNotification) extends AuthConfig
