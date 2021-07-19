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

import akka.Done
import akka.actor.DeadLetter
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model._
import akka.util.Timeout
import com.github.cloudfiles.core.http.HttpRequestSender.{DiscardEntityMode, FailedResponseException}
import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.Fault
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object HttpRequestSenderITSpec {
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
class HttpRequestSenderITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar
  with AsyncTestHelper with WireMockSupport {

  override protected val resourceRoot: String = "core"

  import HttpRequestSenderITSpec._

  "HttpRequestSender" should "send an HTTP request" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile("response.txt")))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = Path), RequestData, probe.ref)

    actor ! request
    val result = probe.expectMessageType[HttpRequestSender.SuccessResult]
    result.request should be(request)
    result.response.status should be(StatusCodes.Accepted)

    val content = futureResult(entityToString(result.response))
    content should be(FileTestHelper.TestDataSingleLine)
  }

  it should "return a failed result for a non-success response" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.BadRequest.intValue)
        .withBodyFile("response.txt")))
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
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
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri, 64))
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
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = Path), RequestData, probe.ref)

    actor ! request
    val failedResult = probe.expectMessageType[HttpRequestSender.FailedResult]
    failedResult.request should be(request)
    failedResult.cause should not be null
  }

  it should "shutdown the request queue when it is stopped" in {
    val queue = mock[RequestQueue]
    val actor = testKit.spawn(HttpRequestSender.create(serverBaseUri, _ => queue))

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

  it should "support sending requests via a convenience function" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile("response.txt")))
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
    val request = HttpRequest(uri = Path)

    val result = futureResult(HttpRequestSender.sendRequest(actor, request, RequestData))
    result.request.request should be(request)
    result.request.data should be(RequestData)

    result match {
      case HttpRequestSender.SuccessResult(_, response) =>
        response.status should be(StatusCodes.Accepted)
        val content = futureResult(entityToString(response))
        content should be(FileTestHelper.TestDataSingleLine)

      case res => fail("Unexpected result: " + res)
    }
  }

  it should "discard the entity bytes for a failed result" in {
    val result = HttpRequestSender.FailedResult(null, new Exception("don't care"))

    val discardedResult = futureResult(HttpRequestSender.discardEntityBytes(result))
    discardedResult should be(result)
  }

  it should "discard the entity bytes of a successful result" in {
    val entity = mock[ResponseEntity]
    val discardedEntity = new HttpMessage.DiscardedEntity(Future.successful(Done))
    when(entity.discardBytes()).thenReturn(discardedEntity)
    val response = HttpResponse(entity = entity)
    val result = HttpRequestSender.SuccessResult(null, response)

    val discardedResult = futureResult(HttpRequestSender.discardEntityBytes(result))
    discardedResult should be(result)
    Mockito.verify(entity).discardBytes()
  }

  it should "discard the entity bytes of a successful result future" in {
    val entity = mock[ResponseEntity]
    val discardedEntity = new HttpMessage.DiscardedEntity(Future.successful(Done))
    when(entity.discardBytes()).thenReturn(discardedEntity)
    val response = HttpResponse(entity = entity)
    val result = HttpRequestSender.SuccessResult(null, response)
    val futResult = Future.successful(result)

    val discardedResult = futureResult(HttpRequestSender.discardEntityBytes(futResult))
    discardedResult should be(result)
    Mockito.verify(entity).discardBytes()
  }

  it should "support sending requests via a convenience function that checks for success results" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile("response.txt")))
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
    val request = HttpRequest(uri = Path)

    val result = futureResult(HttpRequestSender.sendRequestSuccess(actor, request, RequestData))
    result.request.request should be(request)
    result.request.data should be(RequestData)

    result match {
      case HttpRequestSender.SuccessResult(_, response) =>
        response.status should be(StatusCodes.Accepted)
        val content = futureResult(entityToString(response))
        content should be(FileTestHelper.TestDataSingleLine)

      case res => fail("Unexpected result: " + res)
    }
  }

  it should "support sending requests via a convenience function that handles failed results" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.BadRequest.intValue)))
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
    val request = HttpRequest(uri = Path)

    val exception = expectFailedFuture[FailedResponseException](HttpRequestSender.sendRequestSuccess(actor,
      request, RequestData))
    exception.response.status should be(StatusCodes.BadRequest)
  }

  it should "allow keeping the entity bytes if failure responses are received" in {
    val ErrorPath = "/error"
    val ErrorEntity = "This is an error!"
    stubFor(get(urlPathEqualTo(ErrorPath))
      .willReturn(aResponse()
        .withStatus(StatusCodes.BadRequest.intValue)
        .withBody(ErrorEntity)))
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
    val request = HttpRequest(uri = ErrorPath)

    val result = futureResult(HttpRequestSender.sendRequest(actor, request, RequestData, DiscardEntityMode.Never))
    result match {
      case HttpRequestSender.FailedResult(_, cause: FailedResponseException) =>
        futureResult(entityToString(cause.response)) should be(ErrorEntity)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "support discarding the entity bytes always" in {
    implicit val ec: ExecutionContext = system.executionContext
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))
    val request = HttpRequest(uri = Path)

    val futResults = (1 to 16) map { _ =>
      HttpRequestSender.sendRequestSuccess(actor, request, RequestData, discardMode = DiscardEntityMode.Always)
    }
    futureResult(Future.sequence(futResults))
  }

  it should "answer pending requests when it is stopped" in {
    val request = HttpRequest(uri = Path)
    stubFor(get(anyUrl())
      .willReturn(aResponse().withFixedDelay(10000)
        .withStatus(StatusCodes.OK.intValue)
        .withBody("Delayed response")))
    val actor = testKit.spawn(HttpRequestSender(serverBaseUri))

    def checkFailedResult(futReq: Future[HttpRequestSender.Result], expData: String): Unit = {
      futureResult(futReq) match {
        case res: HttpRequestSender.FailedResult =>
          res.request.request should be(request)
          res.request.data should be(expData)
          res.cause shouldBe a[IOException]
        case res => fail("Unexpected result: " + res)
      }
    }

    val futReq1 = HttpRequestSender.sendRequest(actor, request, "foo")
    val futReq2 = HttpRequestSender.sendRequest(actor, request, "bar")
    actor ! HttpRequestSender.Stop
    checkFailedResult(futReq1, "foo")
    checkFailedResult(futReq2, "bar")
  }
}
