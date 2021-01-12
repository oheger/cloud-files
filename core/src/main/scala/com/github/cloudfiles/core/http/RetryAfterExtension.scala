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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.headers.{RetryAfterDateTime, RetryAfterDuration, `Retry-After`}
import akka.http.scaladsl.model.{DateTime, HttpResponse, StatusCodes}
import com.github.cloudfiles.core.http.HttpRequestSender.{FailedResponseException, FailedResult, ForwardedResult}

import scala.concurrent.duration._

/**
 * An extension actor that intercepts requests, which failed with a 429 - TOO
 * MANY REQUESTS status code and retries them after a delay.
 *
 * When interacting with public servers and downloading or uploading many small
 * files, the server can reject requests with the failure status 429. In this
 * case, the response should typically contain a ''Retry-After'' header that
 * determines when further requests are accepted again. The actor evaluates
 * this header and re-schedules the execution of the request when this point of
 * time is reached.
 *
 * The actor can be configured with a minimum delay, which is also used if a
 * response does not contain a ''Retry-After'' header.
 */
object RetryAfterExtension {
  /** The default minimum delay when handling 429 responses. */
  final val DefaultMinimumDelay = 100.millis

  /**
   * Returns the behavior of a new actor instance. Requests are forwarded to
   * the given request actor. The minimum delay provided is a lower bound for
   * the delays used by this actor; it is also applied if no ''Retry-After''
   * header is found in the response.
   *
   * @param requestSender the request sender to decorate
   * @param minimumDelay  the minimum delay when retrying a request
   * @return the behavior of the new actor
   */
  def apply(requestSender: ActorRef[HttpRequestSender.HttpCommand],
            minimumDelay: FiniteDuration = DefaultMinimumDelay): Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.receivePartial {
      case (context, request: HttpRequestSender.SendRequest) =>
        HttpRequestSender.forwardRequest(context, requestSender, request.request, request)
        Behaviors.same

      case (context, ForwardedResult(FailedResult(HttpRequestSender.SendRequest(request,
      data: HttpRequestSender.SendRequest, _, _), cause: FailedResponseException)))
        if cause.response.status == StatusCodes.TooManyRequests =>
        val delay = delayForRetry(cause.response, minimumDelay)
        context.scheduleOnce(delay, context.self, data)
        context.log.info("Received status 429 for {} {}. Retrying after {}", request.method, request.uri, delay)
        Behaviors.same

      case (_, ForwardedResult(fwdResult)) =>
        val result = HttpRequestSender.resultFromForwardedRequest(fwdResult)
        result.request.replyTo ! result
        Behaviors.same

      case (_, HttpRequestSender.Stop) =>
        requestSender ! HttpRequestSender.Stop
        Behaviors.stopped
    }

  /**
   * Determines the delay for retrying the current request. This is obtained
   * from the ''Retry-After'' header if present; otherwise, the minimum delay
   * passed to the actor is taken into account.
   *
   * @param response     the response of the failed request
   * @param minimumDelay the minimum delay to use
   * @return the delay for retrying the operation
   */
  private def delayForRetry(response: HttpResponse, minimumDelay: FiniteDuration): FiniteDuration = {
    val delayFromHeader = response.header[`Retry-After`]
      .map(_.delaySecondsOrDateTime)
      .map {
        case RetryAfterDuration(delay) => delay.seconds
        case RetryAfterDateTime(dateTime) =>
          val delta = dateTime.clicks - DateTime.now.clicks
          delta.millis
      } getOrElse 0.seconds
    if (delayFromHeader > minimumDelay) delayFromHeader
    else minimumDelay
  }
}
