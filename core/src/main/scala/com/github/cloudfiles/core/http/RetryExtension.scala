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

import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import org.apache.pekko.http.scaladsl.model.HttpResponse

import scala.concurrent.duration._

/**
 * An extension actor that implements a generic retry mechanism for HTTP
 * requests.
 *
 * This extension actor supports a fine-grained configuration under which
 * circumstances and how a request should be retried. It is possible to specify
 * the number of retries for a single request. Also, an exponential backoff is
 * supported. The actor uses a function to determine whether a request should
 * be retried; so, the mechanism does not only work for failed requests but can
 * perform arbitrary checks of the received response.
 */
object RetryExtension {
  /**
   * An alias for a function that is used by this actor to determine whether a
   * request should be retried. The function is passed the result received from
   * the HTTP sender actor and can decide whether to retry the request (in
   * which case it has to return '''true''') or not (in which case it has to
   * return '''false''').
   */
  type RetryCheckFunc = HttpRequestSender.Result => Boolean

  /**
   * A data class defining the configuration for retry behavior with
   * exponential backoff. The properties of this class correspond to the
   * restart facilities supported by actor supervision. Basically, a retry is
   * attempted after increasing durations between the given minimum and maximum
   * backoff durations; an optional random factor can be used to increase the
   * delay to avoid deterministic behavior.
   *
   * @param minBackoff   the minimum backoff duration
   * @param maxBackoff   the maximum backoff duration
   * @param randomFactor the random factor to increase the delay
   */
  case class BackoffConfig(minBackoff: FiniteDuration,
                           maxBackoff: FiniteDuration,
                           randomFactor: Double = 0)

  /**
   * A data class defining the configuration for the retry extension actor.
   *
   * @param optMaxTimes        the optional maximum number of time a retry is
   *                           attempted; if this number is reached, the actor
   *                           responds with the latest failure response; if
   *                           undefined, the actor retries all requests until
   *                           it receives a success response
   * @param optBackoff         the optional configuration for an exponential
   *                           backoff; if undefined, a retry happens
   *                           immediately
   * @param retryCheckFunc     the function to determine whether to retry a
   *                           request or not; the default function retries all
   *                           requests that yield a failed response; n.b. that
   *                           requests that fail completely (without
   *                           generating a response) are '''not''' retried
   * @param discardEntityAfter a timeout when to discard the entity of a failed
   *                           response; a failed response is buffered for a
   *                           while, since it is not immediately known whether
   *                           a retry can happen or not; after buffering the
   *                           response for this time, discarding of the entity
   *                           is enforced; it is usually not necessary to
   *                           change this value; it is used mainly internally
   *                           for testing purposes
   */
  case class RetryConfig(optMaxTimes: Option[Int] = None,
                         optBackoff: Option[BackoffConfig] = None,
                         retryCheckFunc: RetryCheckFunc = RetryOnFailure,
                         discardEntityAfter: FiniteDuration = DefaultDiscardEntityAfter)

  /**
   * A default [[RetryCheckFunc]] implementation that triggers a retry if the
   * status code of the response indicates a failure. If there is no response
   * at all, no retry is attempted.
   */
  private val RetryOnFailure: RetryCheckFunc = {
    case HttpRequestSender.FailedResult(_, FailedResponseException(_)) => true
    case _ => false
  }

  /** A default timeout after which a response entity gets discarded. */
  private val DefaultDiscardEntityAfter = 900.millis

  /**
   * A trait defining the base command processed by the actor responsible for
   * the implementation of the retry logic.
   */
  private sealed trait RetryExtensionCommand

  /**
   * A command that instructs the retry actor to start processing of a new
   * request.
   *
   * @param request the request to process
   */
  private case class HandleRequest(request: HttpRequestSender.SendRequest) extends RetryExtensionCommand

  /**
   * A command that notifies the retry actor about a result becoming available
   * for a specific request. The result is stored, so that it can be sent to
   * the original caller once it is known to be final.
   *
   * @param index  the index of the request
   * @param result the result to store
   */
  private case class ResultReceived(index: Long,
                                    result: HttpRequestSender.Result) extends RetryExtensionCommand

  /**
   * A command that notifies the retry actor that the handler actor for a
   * specific request has stopped. This means that this request is now done
   * (either in success or failure state), and the last response can be sent to
   * the caller.
   *
   * @param index the index of the affected request
   */
  private case class RequestHandlerStopped(index: Long) extends RetryExtensionCommand

  /**
   * A command that notifies the retry actor that the timeout for discarding a
   * response entity is now reached. This means that the request is retried,
   * and the affected result is not sent to the caller. On handling this
   * command, the actor discards the bytes of the response entity.
   *
   * @param response the affected response
   */
  private case class DiscardResponseEntityTimeout(response: HttpResponse) extends RetryExtensionCommand

  /**
   * A data class storing information about a single request that is currently
   * processed by this actor. The class is used to keep track about the results
   * already received, so that it is always possible to inform the original
   * client even if a retry is no longer possible.
   *
   * @param lastResult         the last result received from the sender actor
   * @param discardCancellable the object to cancel the postponed discarding
   *                           of the response entity if available
   */
  private case class RequestState(lastResult: HttpRequestSender.Result,
                                  discardCancellable: Option[Cancellable])

  /**
   * A data class holding the current state of the actor that handles requests
   * with retry logic.
   *
   * @param requestSender the actor for sending requests
   * @param config        the configuration for this extension actor
   * @param requestCount  the counter for processed requests to generate
   *                      unique identifiers
   * @param requests      a map with the requests currently in progress
   */
  private case class RetryActorState(requestSender: ActorRef[HttpRequestSender.HttpCommand],
                                     config: RetryConfig,
                                     requestCount: Long,
                                     requests: Map[Long, RequestState])

  /**
   * Returns the behavior of a new actor instance that forwards to the given
   * request sender actor and uses the specified configuration.
   *
   * @param requestSender the request sender to decorate
   * @param config        the configuration for this extension actor
   * @return the behavior of the new actor
   */
  def apply(requestSender: ActorRef[HttpRequestSender.HttpCommand],
            config: RetryConfig): Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.setup[HttpRequestSender.HttpCommand] { context =>
      val retryState = RetryActorState(
        requestSender = requestSender,
        config = config,
        requestCount = 0,
        requests = Map.empty
      )
      val retryActor = context.spawnAnonymous(handleRequestWithRetry(retryState))

      Behaviors.receiveMessagePartial {
        case request: HttpRequestSender.SendRequest =>
          retryActor ! HandleRequest(request)
          Behaviors.same

        case HttpRequestSender.Stop =>
          requestSender ! HttpRequestSender.Stop
          Behaviors.stopped
      }
    }

  /**
   * The main command handler function of the actor implementing the logic for
   * processing requests. The idea here is to have a temporary actor that does
   * the forwarding of the request to the sender actor and that crashes itself
   * on a failed request. By using a proper resume strategy, the retry logic is
   * enforced automatically: the actor will be restarted as configured and
   * every time send another request until the request succeeds or the
   * supervisor strategy decides that it is enough.
   *
   * @param state the current state of this actor
   * @return the behavior for the retry actor
   */
  private def handleRequestWithRetry(state: RetryActorState): Behavior[RetryExtensionCommand] =
    Behaviors.receive {
      case (context, HandleRequest(request)) =>
        val nextIndex = state.requestCount + 1
        val requestBehavior = handleSendRequest(
          context.self,
          state.requestSender,
          request,
          state.config.retryCheckFunc,
          nextIndex
        )
        val requestActor = context.spawnAnonymous(supervise(state.config, requestBehavior))
        context.watchWith(requestActor, RequestHandlerStopped(nextIndex))
        handleRequestWithRetry(state.copy(requestCount = nextIndex))

      case (context, ResultReceived(index, result)) =>
        val discardCancellable = extractResponse(result).map { response =>
          context.scheduleOnce(state.config.discardEntityAfter, context.self, DiscardResponseEntityTimeout(response))
        }
        val requestState = RequestState(result, discardCancellable)
        val nextState = state.copy(requests = state.requests + (index -> requestState))
        handleRequestWithRetry(nextState)

      case (context, DiscardResponseEntityTimeout(response)) =>
        implicit val mat: ActorSystem[Nothing] = context.system
        response.entity.discardBytes()
        Behaviors.same

      case (_, RequestHandlerStopped(index)) =>
        state.requests.get(index).map { requestState =>
          requestState.lastResult.request.replyTo ! requestState.lastResult
          requestState.discardCancellable.foreach(_.cancel())
          val nextState = state.copy(requests = state.requests - index)
          handleRequestWithRetry(nextState)
        }.getOrElse(Behaviors.same)
    }

  /**
   * The command handler function of a temporary actor that is responsible for
   * a single request. The actor passes the request to the request sender actor
   * and evaluates the result. The result is always passed to the controller
   * actor. In case of a failure, this actor crashes to force supervision to
   * kick in.
   *
   * @param controller    the actor controlling the retry logic
   * @param requestSender the actor for sending requests
   * @param request       the request to be handled
   * @param checkFunc     the function to check for failed requests
   * @param index         the index of this actor (used as ID)
   * @return the behavior for the request handler actor
   */
  private def handleSendRequest(controller: ActorRef[RetryExtensionCommand],
                                requestSender: ActorRef[HttpRequestSender.HttpCommand],
                                request: HttpRequestSender.SendRequest,
                                checkFunc: RetryCheckFunc,
                                index: Long): Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.setup[HttpRequestSender.HttpCommand] { context =>
      val requestStr = s"${request.request.method} ${request.request.uri}"
      HttpRequestSender.forwardRequest(context, requestSender, request.request, request, request.discardEntityMode)

      Behaviors.receiveMessagePartial {
        case HttpRequestSender.ForwardedResult(fwdResult) =>
          val result = HttpRequestSender.resultFromForwardedRequest(fwdResult)
          controller ! ResultReceived(index, result)
          if (checkFunc(result)) {
            // The result is not accepted; so crash to trigger a restart if possible.
            context.log.warn("Received an unaccepted response for {}.", requestStr)
            throw new RuntimeException(requestStr)
          } else {
            Behaviors.stopped
          }
      }
    }

  /**
   * Returns a [[Behavior]] based on the passed in one that is supervised by a
   * strategy compatible with the given configuration.
   *
   * @param config   the configuration for the retry extension
   * @param behavior the original [[Behavior]]
   * @return the supervised [[Behavior]]
   */
  private def supervise(config: RetryConfig, behavior: Behavior[HttpRequestSender.HttpCommand]):
  Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.supervise(behavior).onFailure[Throwable](supervisionStrategy(config))

  /**
   * Returns a [[SupervisorStrategy]] that is compatible with the passed in
   * configuration.
   *
   * @param config the configuration for the retry extension
   * @return the [[SupervisorStrategy]] that follows this configuration
   */
  private def supervisionStrategy(config: RetryConfig): SupervisorStrategy =
    config.optBackoff match {
      case Some(backOffConfig) =>
        val strategy = SupervisorStrategy.restartWithBackoff(
          backOffConfig.minBackoff,
          backOffConfig.maxBackoff,
          backOffConfig.randomFactor
        )
        config.optMaxTimes.fold(strategy)(times => strategy.withMaxRestarts(times))
      case None =>
        val strategy = SupervisorStrategy.restart
        config.optMaxTimes.fold(strategy)(times => strategy.withLimit(times, 1.day))
    }

  /**
   * Extracts the [[HttpResponse]] from the given result if it is available.
   *
   * @param result the result
   * @return an [[Option]] with the extracted result
   */
  private def extractResponse(result: HttpRequestSender.Result): Option[HttpResponse] =
    result match {
      case HttpRequestSender.SuccessResult(_, response) => Some(response)
      case HttpRequestSender.FailedResult(request, FailedResponseException(response))
        if request.discardEntityMode == HttpRequestSender.DiscardEntityMode.Never => Some(response)
      case _ => None
    }
}
