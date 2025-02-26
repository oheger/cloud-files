/*
 * Copyright 2020-2025 The Developers Team.
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

import com.github.cloudfiles.core.http.MultiHostExtension.RequestActorFactory
import com.github.cloudfiles.core.http.auth._
import com.github.cloudfiles.core.http.{HttpRequestSender, MultiHostExtension, RetryAfterExtension}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.Uri

/**
 * A default implementation of [[HttpRequestSenderFactory]].
 */
object HttpRequestSenderFactoryImpl extends HttpRequestSenderFactory {
  /** Suffix for the name generated for a Basic Auth extension. */
  final val BasicAuthName = "_BasicAuth"

  /** Suffix for the name generated for an OAuth extension. */
  final val OAuthName = "_OAuth"

  /** Suffix for the name generated for the actor for IDP communication. */
  final val IDPName = "_IDP"

  /** Suffix for the name generated for a RetryAfter extension. */
  final val RetryAfterName = "_Retry"

  override def createRequestSender(spawner: Spawner, baseUri: Uri, config: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand] =
    decorateRequestSender(spawner, spawner.spawn(HttpRequestSender(baseUri, config.queueSize, config.proxy),
      config.actorName), config)

  override def createMultiHostRequestSender(spawner: Spawner, config: HttpRequestSenderConfig,
                                            requestActorFactory: RequestActorFactory):
  ActorRef[HttpRequestSender.HttpCommand] =
    decorateRequestSender(spawner, spawner.spawn(MultiHostExtension(config.queueSize, proxy = config.proxy,
      requestActorFactory = requestActorFactory), config.actorName), config)

  override def decorateRequestSender(spawner: Spawner, requestSender: ActorRef[HttpRequestSender.HttpCommand],
                                     config: HttpRequestSenderConfig): ActorRef[HttpRequestSender.HttpCommand] = {
    val authActor = config.authConfig match {
      case basicAuth: BasicAuthConfig =>
        spawner.spawn(BasicAuthExtension(requestSender, basicAuth), actorName(config, BasicAuthName))
      case oauthConfig: OAuthConfig =>
        val idpActor = spawner.spawn(HttpRequestSender(oauthConfig.tokenEndpoint, config.queueSize),
          actorName(config, IDPName))
        spawner.spawn(OAuthExtension(requestSender, idpActor, oauthConfig), actorName(config, OAuthName))
      case NoAuthConfig => requestSender
    }

    config.retryAfterConfig.fold(authActor) { retryConfig =>
      spawner.spawn(RetryAfterExtension(authActor, retryConfig), actorName(config, RetryAfterName))
    }
  }

  /**
   * Convenience function to generate an optional actor name derived from the
   * base actor name as specified in the configuration.
   *
   * @param config the ''HttpRequestSenderConfig''
   * @param suffix the suffix to append to the base actor name
   * @return the resulting optional actor name
   */
  private def actorName(config: HttpRequestSenderConfig, suffix: String): Option[String] =
    config.actorName map (_ + suffix)
}
