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

import com.github.cloudfiles.core.http.HttpRequestSender.{DiscardEntityMode, FailedResponseException}
import com.github.cloudfiles.core.http.HttpRequestSenderSpec.TestExtension
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.{never, verify}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

object HttpRequestSenderSpec {

  /**
   * A test actor implementation simulating an extension. This is used to test
   * forwarding messages to another request actor.
   */
  object TestExtension {
    def apply(requestActor: ActorRef[HttpRequestSender.HttpCommand], optTimeout: Option[Timeout] = None):
    Behavior[HttpRequestSender.HttpCommand] =
      Behaviors.receivePartial {
        case (context, request: HttpRequestSender.SendRequest) =>
          optTimeout match {
            case Some(timeout) =>
              HttpRequestSender.forwardRequest(context, requestActor, request.request, request, timeout = timeout)
            case None =>
              HttpRequestSender.forwardRequest(context, requestActor, request.request, request,
                discardMode = DiscardEntityMode.Never)
          }
          Behaviors.same

        case (_, HttpRequestSender.ForwardedResult(result@HttpRequestSender.SuccessResult(
        HttpRequestSender.SendRequest(_, orgRequest: HttpRequestSender.SendRequest, _, _), _))) =>
          orgRequest.replyTo ! result
          Behaviors.same

        case (_, HttpRequestSender.ForwardedResult(result@HttpRequestSender.FailedResult(
        HttpRequestSender.SendRequest(_, orgRequest: HttpRequestSender.SendRequest, _, _), _))) =>
          orgRequest.replyTo ! result
          Behaviors.same
      }
  }

}

/**
 * Test class for ''HttpRequestSender''. This test class tests functionality,
 * which is not yet covered by the integration test.
 */
class HttpRequestSenderSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar {
  "HttpRequestSender" should "forward a request to another request actor" in {
    val httpRequest = HttpRequest(uri = Uri("https://www.test.uri/test"))
    val response = HttpResponse(status = StatusCodes.Created)
    val probeRequestActor = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeSender = testKit.createTestProbe[HttpRequestSender.Result]()
    val extension = testKit.spawn(TestExtension(probeRequestActor.ref))
    val request = HttpRequestSender.SendRequest(httpRequest, "someData", probeSender.ref)

    extension ! request
    val forwardedRequest = probeRequestActor.expectMessageType[HttpRequestSender.SendRequest]
    forwardedRequest.discardEntityMode should be(DiscardEntityMode.Never)
    val forwardResult = HttpRequestSender.SuccessResult(forwardedRequest, response)
    forwardedRequest.replyTo ! forwardResult
    val result = probeSender.expectMessageType[HttpRequestSender.SuccessResult]
    result.response should be(response)
    result.request.request should be(httpRequest)
  }

  it should "handle a timeout when forwarding a request" in {
    val httpRequest = HttpRequest(uri = Uri("https://www.test.uri/test"))
    val probeRequestActor = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeSender = testKit.createTestProbe[HttpRequestSender.Result]()
    val extension = testKit.spawn(TestExtension(probeRequestActor.ref, Some(Timeout(500.millis))))
    val request = HttpRequestSender.SendRequest(httpRequest, "someData", probeSender.ref)

    extension ! request
    val result = probeSender.expectMessageType[HttpRequestSender.FailedResult]
    result.request.request should be(httpRequest)
    result.cause shouldBe a[TimeoutException]
  }

  "FailedResult" should "discard the response entity for discard mode Never" in {
    val entity = mock[ResponseEntity]
    val response = HttpResponse(entity = entity)
    val request = HttpRequestSender.SendRequest(HttpRequest(), null, null, DiscardEntityMode.Never)
    val result = HttpRequestSender.FailedResult(request, FailedResponseException(response))

    result.ensureResponseEntityDiscarded() should be(result)
    verify(entity).discardBytes()
  }

  it should "discard nothing if no response is available" in {
    val request = HttpRequestSender.SendRequest(HttpRequest(), null, null, DiscardEntityMode.Never)
    val result = HttpRequestSender.FailedResult(request, new IllegalStateException("Crashed"))

    result.ensureResponseEntityDiscarded() should be(result)
  }

  it should "not discard the response entity for discard mode Always" in {
    val entity = mock[ResponseEntity]
    val response = HttpResponse(entity = entity)
    val request = HttpRequestSender.SendRequest(HttpRequest(), null, null, DiscardEntityMode.Always)
    val result = HttpRequestSender.FailedResult(request, FailedResponseException(response))

    result.ensureResponseEntityDiscarded() should be(result)
    verify(entity, never()).discardBytes()
  }

  it should "not discard the response entity for discard mode OnFailure" in {
    val entity = mock[ResponseEntity]
    val response = HttpResponse(entity = entity)
    val request = HttpRequestSender.SendRequest(HttpRequest(), null, null, DiscardEntityMode.OnFailure)
    val result = HttpRequestSender.FailedResult(request, FailedResponseException(response))

    result.ensureResponseEntityDiscarded() should be(result)
    verify(entity, never()).discardBytes()
  }

  "FailedResponseException" should "initialize the exception message with the response status" in {
    val response = HttpResponse(status = StatusCodes.BadRequest)

    val exception = FailedResponseException(response)
    exception.getMessage should include(response.status.value)
    exception.getMessage should include(response.status.reason())
  }
}
