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

import com.github.cloudfiles.http.Secret

/**
 * A trait representing a configuration for an authentication mechanism.
 *
 * This is just a marker interface. Concrete implementations define their own
 * specific properties.
 */
sealed trait AuthConfig

/**
 * A data class that collects user credentials for accessing a server via basic
 * auth.
 *
 * @param user     the user name
 * @param password the password
 */
case class BasicAuthConfig(user: String, password: Secret) extends AuthConfig

/**
 * A data class collecting the properties required for an OAuth client
 * application.
 *
 * An instance of this class stores the information required for obtaining an
 * OAuth access token based on a refresh token from a specific OAuth2 identity
 * provider.
 *
 * @param tokenEndpoint the URI of the token endpoint
 * @param redirectUri   the redirect URI
 * @param clientID      the client ID
 * @param clientSecret  the secret to identify the client
 */
case class OAuthConfig(tokenEndpoint: String,
                       redirectUri: String,
                       clientID: String,
                       clientSecret: Secret) extends AuthConfig
