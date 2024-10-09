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

/**
 * A trait providing some common functionality that is useful for typical
 * implementations of extensions that handle authentication.
 */
trait AuthExtension {
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
