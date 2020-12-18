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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}

/**
 * An actor implementation that adds basic authentication to HTTP requests.
 *
 * Based on the given configuration, a proper ''Authorization'' header is
 * added to HTTP requests before they are forwarded to the actual actor
 * responsible for sending requests.
 */
object BasicAuthExtension {
  def apply(requestSender: ActorRef[HttpRequestSender.HttpCommand], config: BasicAuthConfig):
  Behavior[HttpRequestSender.HttpCommand] = {
    val authHeader = createAuthHeader(config)
    Behaviors.receiveMessage {
      case request: HttpRequestSender.SendRequest =>
        val authHttpRequest = request.request.withHeaders(request.request.headers :+ authHeader)
        val authRequest = request.copy(request = authHttpRequest)
        requestSender ! authRequest
        Behaviors.same

      case HttpRequestSender.Stop =>
        requestSender ! HttpRequestSender.Stop
        Behaviors.stopped
    }
  }

  /**
   * Generates an ''Authorization'' header based on the given configuration.
   *
   * @param config the configuration for basic auth
   * @return a header to authenticate against this WebDav server
   */
  private def createAuthHeader(config: BasicAuthConfig): Authorization =
    Authorization(BasicHttpCredentials(config.user, config.password.secret))
}
