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

package com.github.cloudfiles.core.http.factory

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.ProxySupport.{ProxySelectorFunc, SystemProxy}
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.auth.{AuthConfig, NoAuthConfig}

/**
 * A configuration class describing desired features of an actor for sending
 * HTTP requests.
 *
 * An instance of this class is expected by [[HttpRequestSenderFactory]]. It
 * describes the features the resulting actor must support. The properties all
 * have default values, so client code only needs to configure the properties
 * it wants to change.
 *
 * @param actorName        an optional actor name; if defined, the resulting
 *                         actor is given this name (if multiple actors are
 *                         created for other features, their names are derived
 *                         from this base name); otherwise, anonymous actors
 *                         are created
 * @param authConfig       the configuration for the authentication mechanism
 *                         to be used
 * @param queueSize        the size of the request queue
 * @param proxy            the function to select the proxy
 * @param retryAfterConfig an optional configuration for a ''RetryAfter''
 *                         extension; if present, such an extension is created
 */
case class HttpRequestSenderConfig(actorName: Option[String] = None,
                                   authConfig: AuthConfig = NoAuthConfig,
                                   queueSize: Int = HttpRequestSender.DefaultQueueSize,
                                   proxy: ProxySelectorFunc = SystemProxy,
                                   retryAfterConfig: Option[RetryAfterConfig] = None)
