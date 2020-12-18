/*
 * Copyright 2020 The Developers Team.
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

package com.github.cloudfiles.http

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
