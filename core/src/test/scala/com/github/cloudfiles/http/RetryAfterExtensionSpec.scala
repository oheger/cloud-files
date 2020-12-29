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

import akka.actor.testkit.typed.Effect.Scheduled
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.headers.`Retry-After`
import akka.http.scaladsl.model.{DateTime, HttpHeader, HttpRequest, HttpResponse, StatusCode, StatusCodes}
import com.github.cloudfiles.http.HttpRequestSender.FailedResponseException
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object RetryAfterExtensionSpec {
  /** A test HTTP request sent to the test actor. */
  private val TestRequest = HttpRequest(uri = "https://test.org/foo")

  /** A data object associated with the test request. */
  private val RequestData = "someRequestData"

  /**
   * Creates a ''SendRequest'' object with the test request and the given test
   * probe to receive the reply.
   *
   * @param probe the test probe
   * @return the test ''SendRequest''
   */
  private def createSendRequest(probe: TestProbe[HttpRequestSender.Result]): HttpRequestSender.SendRequest =
    HttpRequestSender.SendRequest(TestRequest, RequestData, probe.ref)

  /**
   * Creates a failed result object with a response that failed with the status
   * code provided.
   *
   * @param request the request to be answered
   * @param status  the status code
   * @param headers headers of the response
   * @return the resulting ''FailedResult'' object
   */
  private def failedResult(request: HttpRequestSender.SendRequest, status: StatusCode, headers: Seq[HttpHeader] = Nil):
  HttpRequestSender.FailedResult =
    HttpRequestSender.FailedResult(request,
      FailedResponseException(HttpResponse(status = status, headers = headers)))
}

/**
 * Test class for ''RetryAfterExtension''.
 */
class RetryAfterExtensionSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers {

  import RetryAfterExtensionSpec._

  /**
   * Expects that a request is forwarded to the underlying request actor and
   * sends a response.
   *
   * @param probeRequest the probe simulating the request actor
   * @param resultFunc   a function to produce the result
   * @tparam A the type of the result
   * @return the result generated by the result function
   */
  private def checkAndAnswerForwardedRequest[A <: HttpRequestSender.Result]
  (probeRequest: TestProbe[HttpRequestSender.HttpCommand])
  (resultFunc: HttpRequestSender.SendRequest => A): A = {
    val fwdRequest = probeRequest.expectMessageType[HttpRequestSender.SendRequest]
    fwdRequest.request should be(TestRequest)
    val result = resultFunc(fwdRequest)
    fwdRequest.replyTo ! result
    result
  }

  "RetryAfterExtension" should "handle a successful request" in {
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probeReply)
    val retryExtension = testKit.spawn(RetryAfterExtension(probeRequest.ref))

    retryExtension ! request
    val result = checkAndAnswerForwardedRequest(probeRequest) { fwdRequest =>
      HttpRequestSender.SuccessResult(fwdRequest, HttpResponse(status = StatusCodes.Accepted))
    }

    val finalResult = probeReply.expectMessageType[HttpRequestSender.SuccessResult]
    finalResult.request should be(request)
    finalResult.response should be(result.response)
  }

  it should "handle a failed request" in {
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probeReply)
    val retryExtension = testKit.spawn(RetryAfterExtension(probeRequest.ref))

    retryExtension ! request
    val result = checkAndAnswerForwardedRequest(probeRequest) { fwdRequest =>
      HttpRequestSender.FailedResult(fwdRequest, new IllegalArgumentException("An exception"))
    }

    val finalResult = probeReply.expectMessageType[HttpRequestSender.FailedResult]
    finalResult.request should be(request)
    finalResult.cause should be(result.cause)
  }

  it should "handle a request that failed with another status code" in {
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probeReply)
    val retryExtension = testKit.spawn(RetryAfterExtension(probeRequest.ref))

    retryExtension ! request
    val result = checkAndAnswerForwardedRequest(probeRequest) { fwdRequest =>
      failedResult(fwdRequest, StatusCodes.BadRequest)
    }

    val finalResult = probeReply.expectMessageType[HttpRequestSender.FailedResult]
    finalResult.request should be(request)
    finalResult.cause should be(result.cause)
  }

  it should "handle a request failing with status 429 without a retry-after header" in {
    val MinDelay = 11.seconds
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probeReply)
    val forwardedRequest = HttpRequestSender.SendRequest(request.request, request, request.replyTo)
    val btk = BehaviorTestKit(RetryAfterExtension(probeRequest.ref, MinDelay))

    btk.run(HttpRequestSender.ForwardedResult(failedResult(forwardedRequest, StatusCodes.TooManyRequests)))
    btk.expectEffect(Scheduled(MinDelay, btk.ref, request))
  }

  it should "handle a request failing with status 429 with a retry-after header and a delay" in {
    val headers = Seq(`Retry-After`(27))
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probeReply)
    val forwardedRequest = HttpRequestSender.SendRequest(request.request, request, request.replyTo)
    val btk = BehaviorTestKit(RetryAfterExtension(probeRequest.ref))

    btk.run(HttpRequestSender.ForwardedResult(failedResult(forwardedRequest, StatusCodes.TooManyRequests, headers)))
    btk.expectEffect(Scheduled(27.seconds, btk.ref, request))
  }

  it should "handle a request failing with status 429 with a retry-after header and a date" in {
    val now = DateTime.now
    val dateAfter = now + 1.minute.toMillis
    val headers = Seq(`Retry-After`(dateAfter))
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probeReply)
    val forwardedRequest = HttpRequestSender.SendRequest(request.request, request, request.replyTo)
    val btk = BehaviorTestKit(RetryAfterExtension(probeRequest.ref))

    btk.run(HttpRequestSender.ForwardedResult(failedResult(forwardedRequest, StatusCodes.TooManyRequests, headers)))
    val scheduled = btk.expectEffectType[Scheduled[HttpRequestSender.HttpCommand]]
    scheduled.message should be(request)
    scheduled.target should be(btk.ref)
    scheduled.delay should be > 55.seconds
    scheduled.delay should be < 65.seconds
  }

  it should "react on a Stop message" in {
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val btk = BehaviorTestKit(RetryAfterExtension(probeRequest.ref))

    btk.run(HttpRequestSender.Stop)
    btk.returnedBehavior should be(Behaviors.stopped)
    probeRequest.expectMessage(HttpRequestSender.Stop)
  }
}