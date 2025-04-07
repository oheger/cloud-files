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

import com.github.cloudfiles.core.http.HttpRequestSender.{DiscardEntityMode, FailedResponseException}
import com.github.cloudfiles.core.http.RetryTestHelper.{TestRequest, failedResult}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, ResponseEntity, StatusCode, StatusCodes}
import org.mockito.Mockito
import org.mockito.Mockito.{never, verify}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

/**
 * Test class for [[RetryExtension]].
 */
class RetryExtensionSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar {
  /**
   * Creates the [[RetryTestHelper]] with the correct behavior to test.
   *
   * @return the test helper
   */
  private def createHelper(config: RetryExtension.RetryConfig): RetryTestHelper =
    new RetryTestHelper(testKit, this)(sender => RetryExtension(sender, config))

  "RetryExtension" should "handle a successful request" in {
    val response = HttpResponse(status = StatusCodes.Accepted)
    val helper = createHelper(RetryExtension.RetryConfig())

    val result = helper.sendTestRequest()
      .checkAndAnswerForwardedRequest { fwdRequest =>
        fwdRequest.discardEntityMode should be(DiscardEntityMode.OnFailure)
        HttpRequestSender.SuccessResult(fwdRequest, response)
      }.expectSuccessResult()
    result.response should be(response)
  }

  it should "propagate the discard entity mode when forwarding a request" in {
    val helper = createHelper(RetryExtension.RetryConfig())

    val fwdRequest = helper.sendTestRequest(DiscardEntityMode.Always)
      .expectForwardedRequest()
    fwdRequest.discardEntityMode should be(DiscardEntityMode.Always)
  }

  it should "handle a failed request" in {
    val exception = new IllegalArgumentException("An exception")
    val helper = createHelper(RetryExtension.RetryConfig())

    val result = helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.FailedResult(_, exception)
      }
      .expectFailureResult()
    result.cause should be(exception)
  }

  it should "correctly evaluate the check function for retries" in {
    val checkFunc: RetryExtension.RetryCheckFunc = result => {
      result.request.request should be(RetryTestHelper.TestRequest)
      result match {
        case HttpRequestSender.FailedResult(_, HttpRequestSender.FailedResponseException(response)) =>
          response.status != StatusCodes.BadRequest
        case _ => true
      }
    }
    val config = RetryExtension.RetryConfig(retryCheckFunc = checkFunc)
    val helper = createHelper(config)

    val result = helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.BadRequest)
      }
      .expectFailureResult()
    result.cause match {
      case e: FailedResponseException =>
        e.response.status should be(StatusCodes.BadRequest)
      case e => fail("Unexpected exception: " + e)
    }
  }

  it should "retry a failed request" in {
    val responseEntity = mock[ResponseEntity]
    val config = RetryExtension.RetryConfig(discardEntityAfter = 1.millis)
    val helper = createHelper(config)

    helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.InternalServerError, entity = responseEntity)
      }
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse())
      }
      .expectSuccessResult()
    verify(responseEntity, never()).discardBytes()
  }

  it should "handle multiple parallel requests" in {
    val otherClient = testKit.createTestProbe[HttpRequestSender.Result]()
    val otherRequest = HttpRequestSender.SendRequest(
      request = HttpRequest(uri = "https://example.com/bar"),
      data = 0,
      replyTo = otherClient.ref
    )
    val helper = createHelper(RetryExtension.RetryConfig())

    val forwardedRequest1 = helper.sendTestRequest()
      .expectForwardedRequest()
    val forwardedRequest2 = helper.sendRequest(otherRequest)
      .expectForwardedRequest()

    forwardedRequest1.request should be(TestRequest)
    forwardedRequest2.request should be(otherRequest.request)
    val response1 = HttpRequestSender.SuccessResult(forwardedRequest1, HttpResponse())
    val response2 = HttpRequestSender.SuccessResult(forwardedRequest2, HttpResponse(status = StatusCodes.Created))
    forwardedRequest1.replyTo ! response1
    forwardedRequest2.replyTo ! response2

    helper.expectSuccessResult()
    val result2 = otherClient.expectMessageType[HttpRequestSender.SuccessResult]
    result2.response.status should be(StatusCodes.Created)
  }

  it should "discard the entity when retrying a request in discard mode Never" in {
    val responseEntity = mock[ResponseEntity]
    val config = RetryExtension.RetryConfig(discardEntityAfter = 10.millis)
    val helper = createHelper(config)

    helper.sendTestRequest(DiscardEntityMode.Never)
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.BadGateway, entity = responseEntity)
      }
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse())
      }
      .expectSuccessResult()
    verify(responseEntity, Mockito.timeout(1000)).discardBytes()
  }

  it should "retry a successful request if the check function demands this" in {
    val responseEntity = mock[ResponseEntity]
    val checkFunc: RetryExtension.RetryCheckFunc = {
      case HttpRequestSender.SuccessResult(_, response) =>
        response.status == StatusCodes.Accepted
      case r =>
        fail("Unexpected result: " + r)
    }
    val config = RetryExtension.RetryConfig(retryCheckFunc = checkFunc,
      discardEntityAfter = 1.millis)
    val helper = createHelper(config)

    val result = helper.sendTestRequest(HttpRequestSender.DiscardEntityMode.Never)
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse(status = StatusCodes.Accepted, entity = responseEntity))
      }
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse(status = StatusCodes.Created))
      }
      .expectSuccessResult()

    result.response.status should be(StatusCodes.Created)
    verify(responseEntity, Mockito.timeout(1000)).discardBytes()
  }

  it should "retry only for the configured number of times" in {
    val RetryCount = 4
    val config = RetryExtension.RetryConfig(optMaxTimes = Some(RetryCount))
    val helper = createHelper(config)

    helper.sendTestRequest()
    (1 to RetryCount).foreach { _ =>
      helper.checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.BadGateway)
      }
    }
    val result = helper.checkAndAnswerForwardedRequest {
      failedResult(_, StatusCodes.BadRequest)
    }.expectFailureResult()
    result.cause match {
      case e: FailedResponseException =>
        e.response.status should be(StatusCodes.BadRequest)
      case e => fail("Unexpected exception: " + e)
    }
  }

  it should "not discard the entity for the request passed to the caller" in {
    val responseEntity = mock[ResponseEntity]
    val config = RetryExtension.RetryConfig(optMaxTimes = Some(1), discardEntityAfter = 1.millis)
    val helper = createHelper(config)

    helper.sendTestRequest(HttpRequestSender.DiscardEntityMode.Never)
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.BadGateway)
      }
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.BadGateway, entity = responseEntity)
      }.expectFailureResult()

    verify(responseEntity, never()).discardBytes()
  }

  /**
   * Expects a forwarded request and sends a failure response. Returns the
   * time (in nanos) when the request was received.
   *
   * @param helper the test helper
   * @param status the status code for the failure response
   * @return the time when the forwarded request was received
   */
  private def checkAndAnswerForwardedRequestWithTime(helper: RetryTestHelper,
                                                     status: StatusCode = StatusCodes.BadGateway): Long = {
    helper.checkAndAnswerForwardedRequest {
      failedResult(_, status)
    }
    System.nanoTime()
  }

  it should "support retry with exponential backoff" in {
    val backOffConfig = RetryExtension.BackoffConfig(50.millis, 10.seconds)
    val config = RetryExtension.RetryConfig(optBackoff = Some(backOffConfig))
    val helper = createHelper(config)

    helper.sendTestRequest()
    val failureTime = checkAndAnswerForwardedRequestWithTime(helper)
    val retryTime1 = checkAndAnswerForwardedRequestWithTime(helper, StatusCodes.InternalServerError)
    val retryTime2 = checkAndAnswerForwardedRequestWithTime(helper)

    val delta1 = (retryTime1 - failureTime).nanos
    delta1 should be >= 50.millis
    val delta2 = (retryTime2 - retryTime1).nanos
    delta2 should be >= 100.millis
  }

  it should "correctly evaluate the upper bound of exponential backoff" in {
    val backOffConfig = RetryExtension.BackoffConfig(500.millis, 750.millis)
    val config = RetryExtension.RetryConfig(optBackoff = Some(backOffConfig))
    val helper = createHelper(config)

    helper.sendTestRequest()
    checkAndAnswerForwardedRequestWithTime(helper)
    val retryTime1 = checkAndAnswerForwardedRequestWithTime(helper, StatusCodes.InternalServerError)
    val retryTime2 = checkAndAnswerForwardedRequestWithTime(helper)

    val delta = (retryTime2 - retryTime1).nanos
    delta should be >= 500.millis
    delta should be < 1000.millis
  }

  it should "retry only for the configured number of times with exponential backoff" in {
    val RetryCount = 3
    val backOffConfig = RetryExtension.BackoffConfig(minBackoff = 10.millis, maxBackoff = 25.millis)
    val config = RetryExtension.RetryConfig(optMaxTimes = Some(RetryCount), optBackoff = Some(backOffConfig))
    val helper = createHelper(config)

    helper.sendTestRequest()
    (1 to RetryCount).foreach { _ =>
      helper.checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.BadGateway)
      }
    }
    val result = helper.checkAndAnswerForwardedRequest {
      failedResult(_, StatusCodes.BadRequest)
    }.expectFailureResult()
    result.cause match {
      case e: FailedResponseException =>
        e.response.status should be(StatusCodes.BadRequest)
      case e => fail("Unexpected exception: " + e)
    }
  }

  it should "handle a Stop command" in {
    val helper = createHelper(RetryExtension.RetryConfig())

    helper.checkHandlingOfStopMessage()
  }
}
