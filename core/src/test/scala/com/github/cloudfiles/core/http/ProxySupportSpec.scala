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

package com.github.cloudfiles.core.http

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import com.github.cloudfiles.core.http.ProxySupport.ProxySpec
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.{net, util}
import java.net.Proxy.Type
import java.net.{InetSocketAddress, ProxySelector, URI}
import java.util.Collections

object ProxySupportSpec {
  /** A test URI used by test cases. */
  private val TestUri = Uri("https://test.example.org/foo.html")

  /** Constant for a test proxy address. */
  private val ProxyAddress = new InetSocketAddress("proxy.example.org", 3128)
}

/**
 * Test class for ''ProxySupport''.
 */
class ProxySupportSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import ProxySupportSpec._

  "ProxySupportSpec" should "provide a selector func that never uses a proxy" in {
    ProxySupport.NoProxy(TestUri) should be(None)
  }

  it should "provide a selector func that uses a specific proxy" in {
    val spec = ProxySpec(ProxyAddress, Some(BasicHttpCredentials("scott", "tiger")))
    val selector = ProxySupport.withProxy(spec)

    selector(TestUri).get should be(spec)
  }

  /**
   * Temporarily changes the default proxy selector and runs the given block.
   * Afterwards, the original proxy selector is restored. This is used to test
   * the selector function that delegates to the Java system proxy.
   *
   * @param selector the selector to install
   * @param block    the code block to execute
   */
  private def withProxySelector(selector: ProxySelector)(block: => Unit): Unit = {
    val original = ProxySelector.getDefault
    try {
      ProxySelector.setDefault(selector)
      block
    } finally {
      ProxySelector.setDefault(original)
    }
  }

  /**
   * Constructs a mock ''ProxySelector'' that returns the given result when
   * queried for the test URI.
   *
   * @param result the result
   * @return the mock proxy selector
   */
  private def createSelector(result: java.util.List[java.net.Proxy]): ProxySelector = {
    val selector = mock[ProxySelector]
    when(selector.select(URI.create(TestUri.toString()))).thenReturn(result)
    selector
  }

  it should "provide a selector func that queries the default proxy selector" in {
    withProxySelector(createSelector(Collections.singletonList(new net.Proxy(Type.HTTP, ProxyAddress)))) {
      ProxySupport.SystemProxy(TestUri) should be(Some(ProxySpec(ProxyAddress)))
    }
  }

  it should "handle an empty list of proxies returned from the selector" in {
    withProxySelector(createSelector(Collections.emptyList())) {
      ProxySupport.SystemProxy(TestUri) should be(None)
    }
  }

  it should "handle a proxy without an address returned from the selector" in {
    withProxySelector(createSelector(Collections.singletonList(net.Proxy.NO_PROXY))) {
      ProxySupport.SystemProxy(TestUri) should be(None)
    }
  }

  it should "iterate over the list of proxies from the selector to find a suitable one" in {
    val proxies = new util.ArrayList[net.Proxy]()
    proxies.add(net.Proxy.NO_PROXY)
    proxies.add(new net.Proxy(Type.HTTP, ProxyAddress))

    withProxySelector(createSelector(proxies)) {
      ProxySupport.SystemProxy(TestUri) should be(Some(ProxySpec(ProxyAddress)))
    }
  }
}
