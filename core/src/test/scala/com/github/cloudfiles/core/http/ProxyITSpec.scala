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

package com.github.cloudfiles.core.http

import com.github.cloudfiles.core.WireMockSupport
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode
import com.github.cloudfiles.core.http.ProxySupport.ProxySpec
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactoryImpl, Spawner}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlPathEqualTo}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.util.Timeout
import org.eclipse.jetty.proxy.{ConnectHandler, ProxyServlet}
import org.eclipse.jetty.server.{NetworkConnector, Server}
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ProxyITSpec {
  /** The test path for requests. */
  final val Path = "/request"

  /** The name of the header with the proxy authorization. */
  final val HeaderProxyAuthorization = "Proxy-Authorization"

  /** A string response produced by the mock request. */
  final val ServerResponse = "test successful"

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
   * A case class to indicate that a request to the proxy was received and to
   * store some properties of the request. Obviously, the internal requests in
   * Jetty are mutable and change their properties during request processing.
   * Therefore, this class is used to copy relevant information.
   *
   * @param authorizationHeader the authorization header passed to the proxy
   */
  case class ProxyRequest(authorizationHeader: String)

  /**
   * Stubs the test request at the WireMock server.
   */
  def stubTestRequest(): Unit = {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBody(ServerResponse)))
  }

  /**
   * Runs a code block with a proxy server active and a function that checks
   * the requests that were sent to it. The proxy server is started before and
   * stopped after the execution of the code blocks. The check function is
   * passed a queue, from which the requests sent to the proxy can be obtained.
   *
   * @param block the code block to execute
   * @param check the check function
   * @return the ''Future'' with the test assertion
   */
  def runWithProxy(block: ProxySpec => Future[Assertion])
                  (check: BlockingQueue[ProxyRequest] => Future[Assertion])
                  (implicit ec: ExecutionContext): Future[Assertion] = {
    val queue = new LinkedBlockingQueue[ProxyRequest]

    val server = new Server(0)
    val proxyHandler = new ConnectHandler {
      // Override this method to verify that the proxy was invoked. Here the
      // request has the most properties.
      override def handleAuthentication(request: HttpServletRequest, response: HttpServletResponse,
                                        address: String): Boolean =
        queue.offer(ProxyRequest(request.getHeader(HeaderProxyAuthorization)))
    }

    server.setHandler(proxyHandler)
    val context = new ServletContextHandler(proxyHandler, "/", ServletContextHandler.SESSIONS)
    val proxyServletHolder = new ServletHolder(classOf[ProxyServlet])
    context.addServlet(proxyServletHolder, "/*")
    server.start()
    val port = server.getConnectors.head.asInstanceOf[NetworkConnector].getLocalPort

    val futBlock = block(ProxySpec(new InetSocketAddress("localhost", port)))
    val futCheck = check(queue)
    val futAssert = for {
      _ <- futBlock
      checkAssert <- futCheck
    } yield checkAssert

    futAssert.andThen {
      case _ => server.stop()
    }
  }

  /**
   * A helper function to retrieve a request from a queue. This can be used to
   * obtain the requests sent to the proxy server.
   *
   * @param queue the queue
   * @return the next request from the queue
   */
  def nextRequest(queue: BlockingQueue[ProxyRequest]): ProxyRequest = {
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
class ProxyITSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers with WireMockSupport {
  override protected val resourceRoot: String = "core"

  import ProxyITSpec._

  /**
   * Checks whether the expected result was received from the mock server.
   *
   * @param result the result
   */
  private def checkResult(result: HttpRequestSender.SuccessResult): Future[Assertion] = {
    result.response.status should be(StatusCodes.Accepted)
    entityToString(result.response) map { responseBody =>
      responseBody should be(ServerResponse)
    }
  }

  "HttpRequestSender" should "use a configured proxy" in {
    stubTestRequest()
    runWithProxy { proxySpec =>
      val actor = testKit.spawn(HttpRequestSender(serverBaseUri, proxy = ProxySupport.withProxy(proxySpec)))
      val request = HttpRequest(uri = Path)

      HttpRequestSender.sendRequestSuccess(actor, request, DiscardEntityMode.Always) map { result =>
        result.response.status should be(StatusCodes.Accepted)
      }
    } { queue =>
      val request = nextRequest(queue)
      request.authorizationHeader should be(null)
    }
  }

  it should "pass credentials to the proxy" in {
    stubTestRequest()
    runWithProxy { proxySpec =>
      val actor = testKit.spawn(HttpRequestSender(serverBaseUri,
        proxy = ProxySupport.withProxy(proxySpec.copy(credentials = Some(ProxyCredentials)))))
      val request = HttpRequest(uri = Path)

      HttpRequestSender.sendRequestSuccess(actor, request) flatMap checkResult
    } { queue =>
      val request = nextRequest(queue)
      request.authorizationHeader should be(CredentialsBase64)
    }
  }

  "MultiHostExtension" should "use a configured proxy" in {
    stubTestRequest()
    runWithProxy { proxySpec =>
      val actor = testKit.spawn(MultiHostExtension(proxy = ProxySupport.withProxy(proxySpec)))
      val request = HttpRequest(uri = serverUri(Path))

      HttpRequestSender.sendRequestSuccess(actor, request, DiscardEntityMode.Always) map { result =>
        result.response.status should be(StatusCodes.Accepted)
      }
    } { queue =>
      nextRequest(queue)
      succeed
    }
  }

  /**
   * Creates a ''Spawner'' implementation based on the current test kit.
   *
   * @return the ''Spawner''
   */
  private def spawner(): Spawner = new Spawner {
    override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
      testKit.spawn(behavior)
  }

  "HttRequestSenderFactoryImpl" should "use a configured proxy for a plain request actor" in {
    stubTestRequest()
    runWithProxy { proxySpec =>
      val config = HttpRequestSenderConfig(proxy = ProxySupport.withProxy(proxySpec))
      val actor = HttpRequestSenderFactoryImpl.createRequestSender(spawner(), serverBaseUri, config)
      val request = HttpRequest(uri = Path)

      HttpRequestSender.sendRequestSuccess(actor, request, DiscardEntityMode.Always) map { result =>
        result.response.status should be(StatusCodes.Accepted)
      }
    } { queue =>
      nextRequest(queue)
      succeed
    }
  }

  it should "use a configured proxy for a multi-request actor" in {
    stubTestRequest()
    runWithProxy { proxySpec =>
      val config = HttpRequestSenderConfig(proxy = ProxySupport.withProxy(proxySpec))
      val actor = HttpRequestSenderFactoryImpl.createMultiHostRequestSender(spawner(), config)
      val request = HttpRequest(uri = serverUri(Path))

      HttpRequestSender.sendRequestSuccess(actor, request, DiscardEntityMode.Always) map { result =>
        result.response.status should be(StatusCodes.Accepted)
      }
    } { queue =>
      nextRequest(queue)
      succeed
    }
  }
}
