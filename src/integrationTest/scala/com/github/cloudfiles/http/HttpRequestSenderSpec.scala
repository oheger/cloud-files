/*
 * Copyright 2020 The Developers Team.
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

package com.github.cloudfiles.http

import akka.actor.DeadLetter
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.stream.scaladsl.Sink
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.{AsyncTestHelper, FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.Fault
import org.mockito.Mockito
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object HttpRequestSenderSpec {
  /** A data object passed with the request. */
  private val RequestData = new Object

  /** A test request path. */
  private val Path = "/foo"

  /** A timeout for querying the actor under test. */
  private implicit val RequestTimeout: Timeout = Timeout(3.seconds)
}

/**
 * Integration test class for ''HttpRequestSender''.
 */
class HttpRequestSenderSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar
  with AsyncTestHelper with WireMockSupport {

  import HttpRequestSenderSpec._

  "HttpRequestSender" should "send a HTTP request" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile("response.txt")))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val actor = testKit.spawn(HttpRequestSender(serverUri("")))
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = Path), RequestData, probe.ref)

    actor ! request
    val result = probe.expectMessageType[HttpRequestSender.SuccessResult]
    result.request should be(request)
    result.response.status should be(StatusCodes.Accepted)

    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val content = futureResult(result.response.entity.dataBytes.runWith(sink))
    content.utf8String should be(FileTestHelper.TestDataSingleLine)
  }

  it should "return a failed future for a non-success response" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.BadRequest.intValue)
        .withBodyFile("response.txt")))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val actor = testKit.spawn(HttpRequestSender(serverUri("")))
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = Path), RequestData, probe.ref)

    actor ! request
    val failedResult = probe.expectMessageType[HttpRequestSender.FailedResult]
    failedResult.request should be(request)
    failedResult.cause match {
      case resp: HttpRequestSender.FailedResponseException =>
        resp.response.status should be(StatusCodes.BadRequest)
      case t => fail(t)
    }
  }

  it should "discard the entity bytes if failure responses are received" in {
    val ErrorPath = "/error"
    stubFor(get(urlPathEqualTo(ErrorPath))
      .willReturn(aResponse()
        .withStatus(StatusCodes.BadRequest.intValue)
        .withBodyFile("response.txt")))
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.OK.intValue)))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val actor = testKit.spawn(HttpRequestSender(serverUri(""), 64))
    val errRequest = HttpRequestSender.SendRequest(HttpRequest(uri = ErrorPath), RequestData, probe.ref)
    (1 to 32) foreach { _ => actor ! errRequest }

    val probeSuc = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = Path), RequestData, probeSuc.ref)
    actor ! request
    probeSuc.expectMessageType[HttpRequestSender.SuccessResult]
  }

  it should "handle an exception from the server" in {
    stubFor(get(anyUrl())
      .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val actor = testKit.spawn(HttpRequestSender(serverUri("")))
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = Path), RequestData, probe.ref)

    actor ! request
    val failedResult = probe.expectMessageType[HttpRequestSender.FailedResult]
    failedResult.request should be(request)
    failedResult.cause should not be null
  }

  it should "shutdown the request queue when it is stopped" in {
    val queue = mock[RequestQueue]
    val actor = testKit.spawn(HttpRequestSender.create(serverUri(""), _ => queue))

    testKit stop actor
    Mockito.verify(queue).shutdown()
  }

  it should "stop itself when receiving a corresponding message" in {
    val probe = testKit.createDeadLetterProbe()
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val actor = testKit.spawn(HttpRequestSender(serverUri(Path)))

    actor ! HttpRequestSender.Stop
    actor ! HttpRequestSender.SendRequest(HttpRequest(uri = Path), RequestData, probeReply.ref)
    probe.expectMessageType[DeadLetter]
  }
}
