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

package com.github.cloudfiles.core.http

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.HttpCredentials

import java.net.{InetSocketAddress, ProxySelector, URI}
import scala.annotation.tailrec

/**
 * A module defining functionality related to proxy support for actors sending
 * HTTP(S) requests.
 *
 * Proxies are enabled by passing a special ''ProxySelectorFunc'' to the
 * ''apply()'' function of [[HttpRequestSender]]. The function then installs a
 * proxy based on the URL to be used by the actor. This module defines a couple
 * of default proxy selector functions.
 */
object ProxySupport {
  /**
   * A class describing a proxy connection to be used when sending HTTP(S)
   * requests.
   *
   * @param address     the address of the proxy server
   * @param credentials optional credentials to authenticate against the proxy
   */
  case class ProxySpec(address: InetSocketAddress, credentials: Option[HttpCredentials] = None)

  /**
   * A function to select a specific proxy server based on a request URI. The
   * function is passed the URI and returns an ''Option'' with the
   * [[ProxySpec]] to use for this URI. A return value of ''None'' indicates a
   * direct internet connection.
   */
  type ProxySelectorFunc = Uri => Option[ProxySpec]

  /**
   * Constant for a ''ProxySelectorFunc'' that never returns a [[ProxySpec]].
   * So this function enforces a direct internet connection.
   */
  final val NoProxy: ProxySelectorFunc = _ => None

  /**
   * Returns a ''ProxySelectorFunc'' that always returns the provided
   * ''ProxySpec'', independent on the request URI. This selector function
   * therefore routes all requests over this proxy.
   *
   * @param spec the ''ProxySpec'' defining the proxy connection
   * @return a selector function using this proxy
   */
  def withProxy(spec: ProxySpec): ProxySelectorFunc = _ => Some(spec)

  /**
   * Constant for a ''ProxySelectorFunc'' that adheres to the proxy settings
   * defined for the current JVM. This function queries the default
   * ''ProxySelector'' for the passed in URI and constructs a ''ProxySpec''
   * based on the result.
   */
  final val SystemProxy: ProxySelectorFunc = uri => {
    val javaUri = URI.create(uri.toString())
    val proxies = ProxySelector.getDefault.select(javaUri)

    // Do not use Java converters to be compatible across Scala versions
    @tailrec def findProxy(index: Int): Option[ProxySpec] = {
      if (index >= proxies.size()) None
      else {
        val proxy = proxies.get(index)
        proxy.address() match {
          case ia: InetSocketAddress => Some(ProxySpec(ia))
          case _ => findProxy(index + 1)
        }
      }
    }

    findProxy(0)
  }
}
