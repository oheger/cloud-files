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

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import com.github.cloudfiles.core.http.auth.{BasicAuthConfig, BasicAuthExtension}
import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlPathEqualTo}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatestplus.mockito.MockitoSugar

/**
 * Integration test class for ''MultiHostExtension''.
 */
class MultiHostExtensionITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with MockitoSugar
  with WireMockSupport with AsyncTestHelper {

  override protected val resourceRoot: String = "core"

  "MultiHostExtension" should "support sending requests to multiple hosts" in {
    val Path1 = "/server1/path"
    val Path2 = "/server2/other_path"
    val Result1 = "First result"
    val Result2 = "This is another result"

    def checkResponse(probe: TestProbe[HttpRequestSender.Result], expRequest: HttpRequestSender.SendRequest,
                      expEntity: String): Unit = {
      val response = probe.expectMessageType[HttpRequestSender.SuccessResult]
      response.request should be(expRequest)
      response.response.status should be(StatusCodes.OK)
      val entity = futureResult(entityToString(response.response))
      entity should be(expEntity)
    }

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
      checkResponse(probe, req1, Result1)
      multiSender ! req2
      checkResponse(probe, req2, Result2)
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
    val factory: MultiHostExtension.RequestActorFactory = (context, uri, queueSize, _) => {
      queueSize should be(RequestQueueSize)
      val requestActor = context.spawnAnonymous(HttpRequestSender(uri))
      context.spawnAnonymous(BasicAuthExtension(requestActor, authConfig))
    }
    stubFor(WireMockSupport.BasicAuthFunc(get(urlPathEqualTo(Path))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(FileTestHelper.TestData))))
    val multiSender = testKit.spawn(MultiHostExtension(RequestQueueSize, requestActorFactory = factory))

    val result = futureResult(HttpRequestSender.sendRequestSuccess(multiSender, HttpRequest(uri = serverUri(Path)),
      null))
    val entity = futureResult(entityToString(result.response))
    entity should be(FileTestHelper.TestData)
  }
}
