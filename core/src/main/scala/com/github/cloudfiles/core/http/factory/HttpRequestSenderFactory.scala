/*
 * Copyright 2020-2023 The Developers Team.
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

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.Uri
import com.github.cloudfiles.core.http.{HttpRequestSender, MultiHostExtension}
import com.github.cloudfiles.core.http.MultiHostExtension.RequestActorFactory

/**
 * A trait to simplify the creation and configuration of actors for sending
 * HTTP requests.
 *
 * The ''http'' package provides several extension actors to modify the
 * behavior of a plain [[HttpRequestSender]] actor, including support for
 * multiple authentication schemes. Usage of these features requires the
 * creation of multiple actors, which are then plugged together.
 *
 * This trait supports a more declarative way of constructing HTTP request
 * sender actors. The idea is to define the desired features of the resulting
 * actor using an [[HttpRequestSenderConfig]] object. A concrete implementation
 * of this trait must evaluate the configuration and construct an actor that
 * supports all the features requested. This not only simplifies client code
 * but also increases testability, as mock implementations of this trait can be
 * used.
 */
trait HttpRequestSenderFactory {
  /**
   * Creates a new actor instance for sending HTTP requests to the specified
   * URI that satisfies the criteria defined by the given configuration. Note
   * that the resulting actor is fully initialized; it is not necessary to call
   * ''decorateRequestSender()'' manually.
   *
   * @param spawner the object to create new actor instances
   * @param baseUri the URI of the host supported by the new actor
   * @param config  the configuration of the request sender actor
   * @return a reference to the newly created actor
   */
  def createRequestSender(spawner: Spawner, baseUri: Uri, config: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand]

  /**
   * Creates a new actor instance for sending HTTP requests to different hosts
   * that satisfies the criteria defined by the given configuration. Note that
   * the resulting actor is fully initialized; it is not necessary to call
   * ''decorateRequestSender()'' manually.
   *
   * @param spawner             the object to create new actor instances
   * @param config              the configuration of the request sender actor
   * @param requestActorFactory a factory for creating new host-specific
   *                            request sender actors
   * @return a reference to the newly created actor
   */
  def createMultiHostRequestSender(spawner: Spawner, config: HttpRequestSenderConfig,
                                   requestActorFactory: RequestActorFactory =
                                   MultiHostExtension.defaultRequestActorFactory):
  ActorRef[HttpRequestSender.HttpCommand]

  /**
   * Decorates the given actor for sending HTTP requests to support all the
   * features declared by the passed in configuration. Adding features to the
   * given actor typically requires the creation of new actors that wrap the
   * original one. The function returns an ''ActorRef'' that has been
   * configured with all desired properties. This function can be used by
   * clients that already have a basic HTTP sender actor and want to enhance
   * it.
   *
   * @param spawner       the object to create new actor instances
   * @param requestSender the original request sender actor to be extended
   * @param config        the configuration of the request sender actor
   * @return a reference to the decorated actor
   */
  def decorateRequestSender(spawner: Spawner, requestSender: ActorRef[HttpRequestSender.HttpCommand],
                            config: HttpRequestSenderConfig): ActorRef[HttpRequestSender.HttpCommand]
}
