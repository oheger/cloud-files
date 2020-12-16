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

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * An actor implementation for sending HTTP requests to a specific host.
 *
 * This module provides an actor-like wrapper around Akka HTTP's host level
 * API. An HTTP request can be sent by passing a message to this actor. The
 * actor processes the request and replies with a corresponding response.
 */
object HttpRequestSender {

  /**
   * The base trait for all commands understood by this actor class.
   */
  sealed trait HttpCommand

  /**
   * A message class that describes a request to be sent. The request is
   * associated with a data object that is also part of the response.
   *
   * @param request the request to be sent
   * @param data    a data object
   * @param replyTo the object to send the reply to
   */
  final case class SendRequest(request: HttpRequest, data: Any, replyTo: ActorRef[Result]) extends HttpCommand

  /**
   * A message causing this actor to stop itself. This can be used to clean up
   * actor instances that are no longer needed.
   */
  final case object Stop extends HttpCommand

  /**
   * A trait describing the result of an HTTP request sent by this actor.
   */
  sealed trait Result

  /**
   * A message class representing a successful response of an HTTP request.
   *
   * @param request  the original request
   * @param response the response to this request
   */
  final case class SuccessResult(request: SendRequest, response: HttpResponse) extends Result

  /**
   * A message class representing a failure response of an HTTP request. The
   * class contains the original request and the exception that was the cause
   * of the failure
   *
   * @param request the original request
   * @param cause   the exception causing the request to fail
   */
  final case class FailedResult(request: SendRequest, cause: Throwable) extends Result

  /**
   * An exception class indicating a response with a non-success status code.
   * The exception contains the original response, so it can be evaluated.
   * Exceptions of this type are contained in a [[FailedResult]] object if the
   * failure was caused by a non-success response.
   *
   * @param response the failed response
   */
  final case class FailedResponseException(response: HttpResponse) extends Exception

  /**
   * An internal message class to process responses when they arrive from the
   * request queue.
   *
   * @param request  the original request
   * @param response the response
   */
  private final case class WrappedHttpResponse(request: SendRequest, response: Try[HttpResponse]) extends HttpCommand

  /** The default size of the request queue. */
  final val DefaultQueueSize = 16

  /**
   * Creates a new actor instance for sending HTTP requests to the host
   * defined by the URI specified.
   *
   * @param uri       the URI defining the target host
   * @param queueSize the size of the request queue
   * @return the behavior of the actor
   */
  def apply(uri: Uri, queueSize: Int = DefaultQueueSize): Behavior[HttpCommand] =
    create(uri, system => new RequestQueue(uri, queueSize)(system))

  /**
   * Internal factory function for creating a new behavior. Simplifies testing.
   *
   * @param uri          the URI defining the target host
   * @param queueCreator the request queue
   * @return the behavior of the actor
   */
  private[http] def create(uri: Uri, queueCreator: ActorSystem => RequestQueue): Behavior[HttpCommand] =
    Behaviors.setup { context =>
      implicit val actorSystem: ActorSystem = context.system.toClassic
      import actorSystem.dispatcher
      val requestQueue = queueCreator(actorSystem)

      Behaviors.receive[HttpCommand] { (context, command) =>
        command match {
          case request: SendRequest =>
            val futResponse = requestQueue.queueRequest(request.request)
            context.pipeToSelf(futResponse) { triedResponse =>
              WrappedHttpResponse(request, triedResponse)
            }
            context.log.info("{} {}", request.request.method.value, request.request.uri)
            Behaviors.same

          case WrappedHttpResponse(request, triedResponse) =>
            triedResponse match {
              case Success(response) =>
                resultFromResponse(context, request)(response) foreach (request.replyTo ! _)
              case Failure(exception) =>
                context.log.error(s"${request.request.method.value} ${request.request.uri} failed!", exception)
                request.replyTo ! FailedResult(request, exception)
            }
            Behaviors.same

          case Stop =>
            Behaviors.stopped
        }

      }
        .receiveSignal {
          case (context, PostStop) =>
            context.log.info("Stopping HttpRequestSender actor for URI {}.", uri)
            requestQueue.shutdown()
            Behaviors.same
        }
    }

  /**
   * Checks the status code of an HTTP response and handles failure
   * responses. If the response is successful, a success result is returned.
   * Otherwise, a failed result is returned, and the entity bytes of the
   * response are discarded.
   *
   * @param req      the original request
   * @param response the response from the server
   * @param system   the actor system
   * @return a future with the generated result
   */
  private def resultFromResponse(context: ActorContext[HttpCommand], req: SendRequest)(response: HttpResponse)
                                (implicit system: ActorSystem): Future[Result] = {
    context.log.debug("{} {} - {} {}", req.request.method.value, req.request.uri,
      response.status.intValue(), response.status.defaultMessage())
    if (response.status.isSuccess())
      Future.successful(SuccessResult(req, response))
    else response.entity.discardBytes().future()
      .map(_ => FailedResult(req, FailedResponseException(response)))(system.dispatcher)
  }
}
