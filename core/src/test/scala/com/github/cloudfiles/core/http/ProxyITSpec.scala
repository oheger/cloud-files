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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.util.Timeout
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.ProxySupport.ProxySpec
import com.github.cloudfiles.core.{AsyncTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlPathEqualTo}
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http
import io.netty.handler.codec.http.HttpObject
import org.littleshoot.proxy.impl.DefaultHttpProxyServer
import org.littleshoot.proxy.{HttpFilters, HttpFiltersAdapter, HttpFiltersSourceAdapter}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.net.{InetSocketAddress, ServerSocket}
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._

object ProxyITSpec {
  /** The test path for requests. */
  final val Path = "/request"

  /** The name of the header with the proxy authorization. */
  final val HeaderProxyAuthorization = "Proxy-Authorization"

  /** A timeout for sending requests. */
  final implicit val RequestTimeout: Timeout = Timeout(3.seconds)

  /** Credentials for the proxy. */
  final val ProxyCredentials = BasicHttpCredentials(WireMockSupport.UserId, WireMockSupport.Password)

  /**
   * The encoded value of the proxy authorization header for the test
   * credentials.
   */
  final val CredentialsBase64 = "Basic c2NvdHQ6dGlnZXI="

  /**
   * Stubs the test request at the WireMock server.
   */
  def stubTestRequest(): Unit = {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)))
  }

  /**
   * Determines a free TCP port that can be used by tests.
   *
   * @return the port number
   */
  def findFreePort(): Int = {
    val socket = new ServerSocket(0)
    try {
      socket.setReuseAddress(true)
      socket.getLocalPort
    } finally {
      socket.close()
    }
  }

  /**
   * Runs a code block with a proxy server active. The proxy server is started
   * before and stopped after the execution of the code block. The function
   * returns a queue, from which the requests sent to the proxy can be
   * obtained.
   *
   * @param block the code block to execute
   * @return a queue to obtain the proxy requests
   */
  def runWithProxy(block: ProxySpec => Unit): BlockingQueue[http.HttpRequest] = {
    val port = findFreePort()
    val queue = new LinkedBlockingQueue[http.HttpRequest]

    val proxy = DefaultHttpProxyServer.bootstrap()
      .withPort(port)
      .withFiltersSource(new HttpFiltersSourceAdapter {
        override def filterRequest(originalRequest: http.HttpRequest, ctx: ChannelHandlerContext): HttpFilters =
          new HttpFiltersAdapter(originalRequest) {
            override def clientToProxyRequest(httpObject: HttpObject): http.HttpResponse = {
              queue.offer(originalRequest)
              null
            }
          }
      }).start()

    try {
      block(ProxySpec(new InetSocketAddress("localhost", port)))
    } finally {
      proxy.stop()
    }

    queue
  }

  /**
   * A helper function to retrieve a request from a queue. This can be used to
   * obtain the requests sent to the proxy server.
   *
   * @param queue the queue
   * @return the next request from the queue
   */
  def nextRequest(queue: BlockingQueue[http.HttpRequest]): http.HttpRequest = {
    val request = queue.poll(RequestTimeout.duration.toMillis, TimeUnit.MILLISECONDS)
    if (request == null) {
      throw new AssertionError("No request received within timeout.")
    }
    request
  }
}

/**
 * An integration test class that tests sending HTTP requests via a proxy
 * server. This class actually starts a proxy server, which records incoming
 * requests.
 */
class ProxyITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with AsyncTestHelper
  with WireMockSupport {
  override protected val resourceRoot: String = "core"

  import ProxyITSpec._

  "HttpRequestSender" should "use a configured proxy" in {
    stubTestRequest()
    val queue = runWithProxy { proxySpec =>
      val actor = testKit.spawn(HttpRequestSender(serverBaseUri, proxy = ProxySupport.withProxy(proxySpec)))
      val request = HttpRequest(uri = Path)

      val result = futureResult(HttpRequestSender.sendRequestSuccess(actor, request, null,
        DiscardEntityMode.Always))
      result.response.status should be(StatusCodes.Accepted)
    }

    val request = nextRequest(queue)
    request.headers().get(HeaderProxyAuthorization) should be(null)
  }

  it should "pass credentials to the proxy" in {
    stubTestRequest()
    val queue = runWithProxy { proxySpec =>
      val actor = testKit.spawn(HttpRequestSender(serverBaseUri,
        proxy = ProxySupport.withProxy(proxySpec.copy(credentials = Some(ProxyCredentials)))))
      val request = HttpRequest(uri = Path)

      val result = futureResult(HttpRequestSender.sendRequestSuccess(actor, request, null,
        DiscardEntityMode.Always))
      result.response.status should be(StatusCodes.Accepted)
    }

    val request = nextRequest(queue)
    request.headers().get(HeaderProxyAuthorization) should be(CredentialsBase64)
  }

  "MultiHostExtension" should "use a configured proxy" in {
    stubTestRequest()
    val queue = runWithProxy { proxySpec =>
      val actor = testKit.spawn(MultiHostExtension(proxy = ProxySupport.withProxy(proxySpec)))
      val request = HttpRequest(uri = serverUri(Path))

      val result = futureResult(HttpRequestSender.sendRequestSuccess(actor, request, null,
        DiscardEntityMode.Always))
      result.response.status should be(StatusCodes.Accepted)
    }

    nextRequest(queue)
  }
}
