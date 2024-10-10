/*
 * Copyright 2020-2024 The Developers Team.
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

import com.github.cloudfiles.core.http.HttpRequestSender
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest}

/**
 * A trait providing some common functionality that is useful for typical
 * implementations of extensions that handle authentication.
 */
trait AuthExtension {
  /**
   * Adds the given auth header (typically a header of type ''Authorization'')
   * to a request if necessary. This function checks whether the given request
   * already contains an ''Authorization'' header. If so, the request is
   * returned as is - except if the value of the header is empty; then it is
   * dropped. This can be used to override the default authorization provided
   * by this extension for single requests.
   *
   * @param request    the request
   * @param authHeader the header to be added
   * @return the modified request
   */
  def withAuthorization(request: HttpRequest, authHeader: => HttpHeader): HttpRequest = {
    request.headers.find(_.is("authorization")) match {
      case Some(auth) if auth.value().isBlank =>
        request.withHeaders(request.headers.filterNot(_.is("authorization")))
      case Some(_) =>
        request
      case None =>
        request.withHeaders(request.headers :+ authHeader)
    }
  }

  /**
   * Handles a command to stop this extension by stopping all the provided
   * managed actors and returning the stopped behavior.
   *
   * @param managedActors the managed actors that need to be stopped as well
   */
  def handleStop(managedActors: ActorRef[HttpRequestSender.HttpCommand]*): Behavior[HttpRequestSender.HttpCommand] = {
    managedActors.foreach(_ ! HttpRequestSender.Stop)
    Behaviors.stopped
  }
}
