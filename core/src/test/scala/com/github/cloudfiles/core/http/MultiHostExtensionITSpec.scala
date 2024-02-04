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

package com.github.cloudfiles.core.http

import com.github.cloudfiles.core.http.auth.{BasicAuthConfig, BasicAuthExtension}
import com.github.cloudfiles.core.{FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlPathEqualTo}
import org.apache.pekko.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Future, Promise}

/**
 * Integration test class for ''MultiHostExtension''.
 */
class MultiHostExtensionITSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with MockitoSugar
  with WireMockSupport {

  override protected val resourceRoot: String = "core"

  /**
   * Checks whether a test request yields the expected response.
   *
   * @param probe      the test probe that receives the response
   * @param expRequest the expected request
   * @param expEntity  the expected entity of the response
   * @return a ''Future'' with the test assertion
   */
  private def checkResponse(probe: TestProbe[HttpRequestSender.Result], expRequest: HttpRequestSender.SendRequest,
                            expEntity: String): Future[Assertion] = {
    val response = probe.expectMessageType[HttpRequestSender.SuccessResult]
    response.request should be(expRequest)
    response.response.status should be(StatusCodes.OK)
    entityToString(response.response) map { entity =>
      entity should be(expEntity)
    }
  }

  "MultiHostExtension" should "support sending requests to multiple hosts" in {
    val Path1 = "/server1/path"
    val Path2 = "/server2/other_path"
    val Result1 = "First result"
    val Result2 = "This is another result"

    runWithNewServer { server2 =>
      stubFor(get(urlPathEqualTo(Path1))
        .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
          .withBody(Result1)))
      server2.stubFor(get(urlPathEqualTo(Path2))
        .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
          .withBody(Result2)))
      val server2Uri = WireMockSupport.serverUri(server2, Path2)
      val probe = testKit.createTestProbe[HttpRequestSender.Result]()
      val req1 = HttpRequestSender.SendRequest(HttpRequest(uri = serverUri(Path1)), "data1", probe.ref)
      val req2 = HttpRequestSender.SendRequest(HttpRequest(uri = server2Uri), "data2", probe.ref)
      val multiSender = testKit.spawn(MultiHostExtension())

      multiSender ! req1
      checkResponse(probe, req1, Result1) flatMap { _ =>
        multiSender ! req2
        checkResponse(probe, req2, Result2)
      }
    }
  }

  it should "handle a Stop request" in {
    val behaviorKit = BehaviorTestKit(MultiHostExtension())

    behaviorKit.run(HttpRequestSender.Stop)
    behaviorKit.returnedBehavior should be(Behaviors.stopped)
  }

  it should "support an alternative actor factory function" in {
    val RequestQueueSize = 53
    val authConfig = BasicAuthConfig(WireMockSupport.UserId, Secret(WireMockSupport.Password))
    val Path = "/data/call"
    val factory: MultiHostExtension.RequestActorFactory = (uri, queueSize, _) => {
      queueSize should be(RequestQueueSize)
      val creator: MultiHostExtension.RequestActorCreator = context => {
        val requestActor = context.spawnAnonymous(HttpRequestSender(uri))
        context.spawnAnonymous(BasicAuthExtension(requestActor, authConfig))
      }
      Future.successful(creator)
    }

    stubFor(WireMockSupport.BasicAuthFunc(get(urlPathEqualTo(Path))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(FileTestHelper.TestData))))
    val multiSender = testKit.spawn(MultiHostExtension(RequestQueueSize, requestActorFactory = factory))

    for {
      result <- HttpRequestSender.sendRequestSuccess(multiSender, HttpRequest(uri = serverUri(Path)), null)
      entity <- entityToString(result.response)
    } yield entity should be(FileTestHelper.TestData)
  }

  it should "handle a failed response from the actor factory" in {
    val expException = new IllegalStateException("Failed request actor creation.")
    val factory: MultiHostExtension.RequestActorFactory = (_, _, _) => Future.failed(expException)
    val testRequest = HttpRequest(uri = "http://www.example.org/test/failure")
    val multiSender = testKit.spawn(MultiHostExtension(requestActorFactory = factory))

    HttpRequestSender.sendRequest(multiSender, testRequest, null) map {
      case HttpRequestSender.FailedResult(request, cause) =>
        request.request should be(testRequest)
        cause should be(expException)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle concurrent creations of request actors" in {
    val Path1 = "/request1"
    val Path2 = "/request2"
    val Result1 = "First result"
    val Result2 = "This is another result"
    stubFor(get(urlPathEqualTo(Path1))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(Result1)))
    stubFor(get(urlPathEqualTo(Path2))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(Result2)))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val req1 = HttpRequestSender.SendRequest(HttpRequest(uri = serverUri(Path1)), "data1", probe.ref)
    val req2 = HttpRequestSender.SendRequest(HttpRequest(uri = serverUri(Path2)), "data2", probe.ref)

    val promiseRequestActor = Promise[MultiHostExtension.RequestActorCreator]()
    val creationCount = new AtomicInteger
    val factory: MultiHostExtension.RequestActorFactory = (uri, _, _) => {
      uri.authority.host.address() should be("localhost")
      if (creationCount.incrementAndGet() == 1) promiseRequestActor.future
      else Future.failed(new IllegalStateException("Unexpected request actor creation."))
    }
    val multiSender = testKit.spawn(MultiHostExtension(requestActorFactory = factory))

    multiSender ! req1
    multiSender ! req2
    val creator: MultiHostExtension.RequestActorCreator = context =>
      context.spawnAnonymous(HttpRequestSender(serverBaseUri))
    promiseRequestActor.success(creator)
    checkResponse(probe, req2, Result2) flatMap { _ =>
      checkResponse(probe, req1, Result1)
    }
  }

  it should "try again after a failed request actor creation" in {
    val Path = "/test/request"
    val Result = "This is the result of the test request."
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(Result)))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val req = HttpRequestSender.SendRequest(HttpRequest(uri = serverUri(Path)), "data1", probe.ref)

    val creationCount = new AtomicInteger
    val factory: MultiHostExtension.RequestActorFactory = (uri, _, _) => {
      if (creationCount.getAndIncrement() == 1) {
        val creator: MultiHostExtension.RequestActorCreator = context =>
          context.spawnAnonymous(HttpRequestSender(uri))
        Future.successful(creator)
      } else Future.failed(new IllegalStateException("Request actor creation failed."))
    }
    val multiSender = testKit.spawn(MultiHostExtension(requestActorFactory = factory))

    multiSender ! req
    probe.expectMessageType[HttpRequestSender.FailedResult]
    multiSender ! req
    checkResponse(probe, req, Result)
  }
}
