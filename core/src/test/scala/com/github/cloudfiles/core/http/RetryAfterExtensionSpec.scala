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

import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.{DiscardEntityMode, FailedResponseException}
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import org.apache.pekko.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.`Retry-After`
import org.mockito.Mockito.{never, verify}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object RetryAfterExtensionSpec {
  /** A test HTTP request sent to the test actor. */
  private val TestRequest = HttpRequest(uri = "https://example.org/foo")

  /** A data object associated with the test request. */
  private val RequestData = "someRequestData"

  /**
   * Creates a failed result object with a response that failed with the status
   * code provided.
   *
   * @param request the request to be answered
   * @param status  the status code
   * @param headers headers of the response
   * @param entity  an optional response entity
   * @return the resulting ''FailedResult'' object
   */
  private def failedResult(request: HttpRequestSender.SendRequest, status: StatusCode,
                           headers: List[HttpHeader] = Nil, entity: ResponseEntity = HttpEntity.Empty):
  HttpRequestSender.FailedResult =
    HttpRequestSender.FailedResult(request,
      FailedResponseException(HttpResponse(status = status, headers = headers, entity = entity)))
}

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

  import RetryAfterExtensionSpec._

  "RetryAfterExtension" should "handle a successful request" in {
    val response = HttpResponse(status = StatusCodes.Accepted)
    val helper = new RetryTestHelper

    val result = helper.sendTestRequest()
      .checkAndAnswerForwardedRequest { fwdRequest =>
        fwdRequest.discardEntityMode should be(DiscardEntityMode.OnFailure)
        HttpRequestSender.SuccessResult(fwdRequest, response)
      }.expectSuccessResult()
    result.response should be(response)
  }

  it should "propagate the discard entity mode when forwarding a request" in {
    val helper = new RetryTestHelper

    val fwdRequest = helper.sendTestRequest(DiscardEntityMode.Always)
      .expectForwardedRequest()
    fwdRequest.discardEntityMode should be(DiscardEntityMode.Always)
  }

  it should "handle a failed request" in {
    val exception = new IllegalArgumentException("An exception")
    val helper = new RetryTestHelper

    val result = helper.sendTestRequest()
      .checkAndAnswerForwardedRequest {
        HttpRequestSender.FailedResult(_, exception)
      }
      .expectFailureResult()
    result.cause should be(exception)
  }

  it should "handle a request that failed with another status code" in {
    val helper = new RetryTestHelper

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
    val helper = new RetryTestHelper

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
    val helper = new RetryTestHelper

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
    val helper = new RetryTestHelper

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
    val helper = new RetryTestHelper

    helper.sendTestRequest()
      .checkAndAnswerForwardedRequest { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.TooManyRequests, headers)
      }.expectNoForwardedRequest()
  }

  it should "discard the entity when retrying a request in discard mode Never" in {
    val responseEntity = mock[ResponseEntity]
    val helper = new RetryTestHelper

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
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val btk = BehaviorTestKit(RetryAfterExtension(probeRequest.ref))

    btk.run(HttpRequestSender.Stop)
    btk.returnedBehavior should be(Behaviors.stopped)
    probeRequest.expectMessage(HttpRequestSender.Stop)
  }

  /**
   * A test helper class managing an actor to tests and helper objects for
   * this purpose.
   */
  private class RetryTestHelper {
    /** A probe acting as the underlying request actor. */
    private val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** A probe acting as the client of the request actor. */
    private val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()

    /** The extension to be tested. */
    private val retryExtension = testKit.spawn(RetryAfterExtension(probeRequest.ref, RetryAfterConfig(1.second)))

    /**
     * Sends a test request to the test extension actor.
     *
     * @param discardMode the entity discard mode to set for this request
     * @return this test helper
     */
    def sendTestRequest(discardMode: DiscardEntityMode = DiscardEntityMode.OnFailure): RetryTestHelper = {
      val request = HttpRequestSender.SendRequest(TestRequest, RequestData, probeReply.ref, discardMode)
      retryExtension ! request
      this
    }

    /**
     * Expects a forwarded request to be sent to the underlying request actor.
     *
     * @return the forwarded request
     */
    def expectForwardedRequest(): HttpRequestSender.SendRequest =
      probeRequest.expectMessageType[HttpRequestSender.SendRequest]

    /**
     * Expects that a request is forwarded to the underlying request actor and
     * sends a response.
     *
     * @param resultFunc a function to produce the result
     * @tparam A the type of the result
     * @return this test helper
     */
    def checkAndAnswerForwardedRequest[A <: HttpRequestSender.Result](resultFunc: HttpRequestSender.SendRequest => A):
    RetryTestHelper = {
      val fwdRequest = expectForwardedRequest()
      fwdRequest.request should be(TestRequest)
      val result = resultFunc(fwdRequest)
      fwdRequest.replyTo ! result
      this
    }

    /**
     * Expects that a successful result is delivered to the client actor. Does
     * some checks on the result.
     *
     * @return the result that was received
     */
    def expectSuccessResult(): HttpRequestSender.SuccessResult =
      checkSendRequest(probeReply.expectMessageType[HttpRequestSender.SuccessResult])

    /**
     * Expects that a failure result is delivered to the client actor. Does
     * some checks on the result.
     *
     * @return the result that was received
     */
    def expectFailureResult(): HttpRequestSender.FailedResult =
      checkSendRequest(probeReply.expectMessageType[HttpRequestSender.FailedResult])

    /**
     * Checks that no message is forwarded to the underlying request actor in a
     * specific time frame. This is used to test whether scheduling of retried
     * requests works as expected.
     *
     * @return this test helper
     */
    def expectNoForwardedRequest(): RetryTestHelper = {
      probeRequest.expectNoMessage(1500.millis)
      this
    }

    /**
     * Checks whether the request contained in the given result has the
     * expected properties of the test request.
     *
     * @param result the result to check
     * @tparam A the type of the result
     * @return the same result
     */
    private def checkSendRequest[A <: HttpRequestSender.Result](result: A): A = {
      result.request.data should be(RequestData)
      result.request.request should be(TestRequest)
      result
    }
  }

}
