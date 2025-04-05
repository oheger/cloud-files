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
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.RetryTestHelper.failedResult
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.`Retry-After`
import org.mockito.Mockito.{never, verify}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

/**
 * Test class for ''RetryAfterExtension''.
 *
 * Note: Ideally, tests of the retry functionality after a given delay would
 * use the synchronous ''BehaviorTestkit''; unfortunately, this does not work
 * here, because this testkit does not provide a classic actor system. Such a
 * system is required, however, by the retry extension to discard the bytes of
 * response entities. Therefore, tests are done asynchronously, and we can only
 * check whether results arrive in a specific time frame or not.
 */
class RetryAfterExtensionSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar {
  /**
   * Creates the [[RetryTestHelper]] with the correct behavior to test.
   *
   * @return the test helper
   */
  private def createHelper(): RetryTestHelper =
    new RetryTestHelper(testKit, this)(sender => RetryAfterExtension(sender, RetryAfterConfig(1.second)))

  "RetryAfterExtension" should "handle a successful request" in {
    val response = HttpResponse(status = StatusCodes.Accepted)
    val helper = createHelper()

    val result = helper.sendTestRequest()
      .checkAndAnswerForwardedRequest { fwdRequest =>
        fwdRequest.discardEntityMode should be(DiscardEntityMode.OnFailure)
        HttpRequestSender.SuccessResult(fwdRequest, response)
      }.expectSuccessResult()
    result.response should be(response)
  }

  it should "propagate the discard entity mode when forwarding a request" in {
    val helper = createHelper()

    val fwdRequest = helper.sendTestRequest(DiscardEntityMode.Always)
      .expectForwardedRequest()
    fwdRequest.discardEntityMode should be(DiscardEntityMode.Always)
  }

  it should "handle a failed request" in {
    val exception = new IllegalArgumentException("An exception")
    val helper = createHelper()

    val result = helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.FailedResult(_, exception)
      }
      .expectFailureResult()
    result.cause should be(exception)
  }

  it should "handle a request that failed with another status code" in {
    val helper = createHelper()

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

  it should "handle a request failing with status 429 without a retry-after header" in {
    val responseEntity = mock[ResponseEntity]
    val helper = createHelper()

    helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.TooManyRequests, entity = responseEntity)
      }
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse())
      }
      .expectSuccessResult()
    verify(responseEntity, never()).discardBytes()
  }

  it should "handle a request failing with status 429 with a retry-after header and a delay" in {
    val headers = List(`Retry-After`(1))
    val helper = createHelper()

    helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.TooManyRequests, headers)
      }
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse())
      }
      .expectSuccessResult()
  }

  it should "handle a request failing with status 429 with a retry-after header and a date" in {
    val now = DateTime.now
    val dateAfter = now + 500 // millis
    val headers = List(`Retry-After`(dateAfter))
    val helper = createHelper()

    helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.TooManyRequests, headers)
      }
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse())
      }
      .expectSuccessResult()
  }

  it should "correctly schedule retries" in {
    val now = DateTime.now
    val dateAfter = now + 1.minute.toMillis
    val headers = List(`Retry-After`(dateAfter))
    val helper = createHelper()

    helper.sendTestRequest()
      .checkAndAnswerForwardedRequest { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.TooManyRequests, headers)
      }.expectNoForwardedRequest()
  }

  it should "discard the entity when retrying a request in discard mode Never" in {
    val responseEntity = mock[ResponseEntity]
    val helper = createHelper()

    helper.sendTestRequest(DiscardEntityMode.Never)
      .checkAndAnswerForwardedRequest {
        failedResult(_, StatusCodes.TooManyRequests, entity = responseEntity)
      }
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.SuccessResult(_, HttpResponse())
      }
      .expectSuccessResult()
    verify(responseEntity).discardBytes()
  }

  it should "react on a Stop message" in {
    val helper = createHelper()

    helper.checkHandlingOfStopMessage()
  }
}
