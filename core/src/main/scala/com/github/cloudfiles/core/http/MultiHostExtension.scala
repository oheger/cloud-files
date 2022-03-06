/*
 * Copyright 2020-2022 The Developers Team.
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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, LoggerOps}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.Uri
import com.github.cloudfiles.core.http.HttpRequestSender.FailedResult
import com.github.cloudfiles.core.http.ProxySupport.{ProxySelectorFunc, SystemProxy}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * An actor implementation that can send HTTP requests to multiple hosts.
 *
 * Being a layer on top of Akka HTTP's host level API, [[HttpRequestSender]]
 * is configured with a single host, to which all requests are sent. This actor
 * implementation overcomes this limitation by creating request actors
 * dynamically whenever a request to a new host is encountered. All request
 * actors managed by an instance are hold in a map, so they are reused. The
 * creation of new child actors is done via a factory function, so it can be
 * customized if necessary.
 *
 * Note that this implementation is not intended to be used as a generic
 * mechanism for sending arbitrary HTTP requests; keeping all the different
 * request actors around would be rather ineffective. Some protocols, however,
 * require that requests are sent to different hosts, for instance when file
 * downloads are handled by a different server than other API calls.
 */
object MultiHostExtension {
  /**
   * A function type that supports the creation of new request actors. This
   * type is used as result by [[RequestActorFactory]]. It is passed an actor
   * context that can be used to create a new request actor reference.
   *
   * Note that this indirection via this function type is necessary because
   * [[RequestActorFactory]] is supposed to return a ''Future''. Running in its
   * own thread, from this future no actors can be created (the ''spawn()''
   * function of ''ActorContext'' is not thread-safe).
   */
  type RequestActorCreator = ActorContext[HttpRequestSender.HttpCommand] => ActorRef[HttpRequestSender.HttpCommand]

  /**
   * A function type to create new request actors. This function is used by the
   * multi-host extension when it encounters a new host for the first time to
   * create the request actor for this host. The function is passed the URI of
   * the current request, the configured size of the request queue, and the
   * configured ''ProxySelectorFunc''. As collecting all the data of the new
   * actor instance may be expensive (e.g. credentials may need to be loaded),
   * it returns a ''Future'' with a [[RequestActorCreator]]. The multi-host
   * extension then invokes this creator to actually create the new request
   * actor.
   */
  type RequestActorFactory = (Uri, Int, ProxySelectorFunc) => Future[RequestActorCreator]

  /**
   * An internally used data class to control the asynchronous creation of
   * request actors for unknown hosts. When all the data to create a new
   * request actor is available, a dummy request with an instance of this class
   * as data object is sent to this actor.
   *
   * @param authority    the authority of the new host
   * @param triedCreator a ''Try'' with the function to create the new request
   *                     actor
   */
  private case class RequestActorCreationData(authority: Uri.Authority,
                                              triedCreator: Try[RequestActorCreator])

  /**
   * An internally used data class holding the state information for an actor
   * instance.
   *
   * Some information stored here is static and is initialized when an instance
   * is created. Other properties may change during the lifetime of an actor.
   *
   * @param requestActors       the map with the current request actors
   * @param pendingCreations    stores information about request actor
   *                            creations in progress
   * @param requestQueueSize    the size of the request queue
   * @param proxy               a function to select the proxy for requests
   * @param requestActorFactory the function to create new request actors
   */
  private case class State(requestActors: Map[Uri.Authority, ActorRef[HttpRequestSender.HttpCommand]],
                           pendingCreations: Map[Uri.Authority, List[HttpRequestSender.SendRequest]],
                           requestQueueSize: Int,
                           proxy: ProxySelectorFunc,
                           requestActorFactory: RequestActorFactory) {
    /**
     * Returns a new ''State'' instance with a map of request actors that
     * contains a new mapping for the given authority and actor ref.
     *
     * @param authority the authority describing the new host
     * @param actor     the request actor for this host
     * @return the update ''State'' instance
     */
    def addRequestActor(authority: Uri.Authority, actor: ActorRef[HttpRequestSender.HttpCommand]): State =
      copy(requestActors = requestActors + (authority -> actor))

    /**
     * Invokes the configured [[RequestActorFactory]] for the given request to
     * produce a new [[RequestActorCreator]] unless there is already a creation
     * for this host in progress. Returns an updated state that reflects the
     * new constellation.
     *
     * @param request the current request
     * @return a tuple with an optional ''Future'' with the creator from the
     *         factory and the updated state
     */
    def triggerRequestActorCreation(request: HttpRequestSender.SendRequest):
    (Option[Future[RequestActorCreator]], State) = {
      val uri = request.request.uri
      if (pendingCreations contains uri.authority) {
        val newCreations = request :: pendingCreations(uri.authority)
        (None, copy(pendingCreations = pendingCreations + (uri.authority -> newCreations)))
      } else
        (Some(requestActorFactory(request.request.uri, requestQueueSize, proxy)),
          copy(pendingCreations = pendingCreations + (uri.authority -> List(request))))
    }

    /**
     * Returns a list with the requests awaiting a request actor creation for
     * the given authority; and a new state, in which these requests have been
     * removed.
     *
     * @param authority the authority
     * @return a tuple with the list of waiting requests and the updated state
     */
    def getAndResetPendingCreations(authority: Uri.Authority): (List[HttpRequestSender.SendRequest], State) = {
      val updatedCreations = pendingCreations - authority
      (pendingCreations(authority), copy(pendingCreations = updatedCreations))
    }
  }

  /**
   * The default factory function for creating new request actors. This
   * function spawns an anonymous, plain [[HttpRequestSender]] actor.
   *
   * @param uri              the URI of the current request
   * @param requestQueueSize the size of the request queue
   * @param proxy            the function to select a proxy
   * @return a reference to the newly created actor
   */
  def defaultRequestActorFactory(uri: Uri, requestQueueSize: Int, proxy: ProxySelectorFunc):
  Future[RequestActorCreator] =
    Future.successful(context => context.spawnAnonymous(HttpRequestSender(uri, requestQueueSize, proxy)))

  /**
   * Creates a new instance of this actor class.
   *
   * @param requestQueueSize    the size of the request queue
   * @param requestActorFactory the function to create new request actors
   * @param proxy               a function to select the proxy for requests
   * @return the initial behavior of the new actor instance
   */
  def apply(requestQueueSize: Int = HttpRequestSender.DefaultQueueSize, proxy: ProxySelectorFunc = SystemProxy,
            requestActorFactory: RequestActorFactory = defaultRequestActorFactory):
  Behavior[HttpRequestSender.HttpCommand] =
    handleRequests(State(Map.empty, Map.empty, requestQueueSize, proxy, requestActorFactory))

  /**
   * Returns the behavior for request handling. The passed in state object is
   * updated when receiving a request for another host, not yet covered.
   *
   * @param state the state of this actor instance
   * @return the behavior to handle requests
   */
  private def handleRequests(state: State): Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.receivePartial {
      case (context, HttpRequestSender.SendRequest(_, creation: RequestActorCreationData, _, _)) =>
        val (pendingRequests, state1) = state.getAndResetPendingCreations(creation.authority)
        creation.triedCreator match {
          case Success(creator) =>
            val newRequestActor = creator(context)
            pendingRequests foreach context.self.!
            handleRequests(state1.addRequestActor(creation.authority, newRequestActor))

          case Failure(exception) =>
            context.log.warn2("Creation of request actor for authority {} failed.",
              creation.authority, exception)
            pendingRequests foreach { request =>
              request.replyTo ! FailedResult(request, exception)
            }
            handleRequests(state1)
        }

      case (context, request: HttpRequestSender.SendRequest) =>
        val authority = request.request.uri.authority
        state.requestActors get authority match {
          case Some(actor) =>
            actor ! request
            Behaviors.same

          case None =>
            val (optCreation, nextState) = state.triggerRequestActorCreation(request)
            optCreation foreach { futCreation =>
              context.log.info("Creating request actor for authority {}.", authority)
              context.pipeToSelf(futCreation) { triedCreator =>
                val creation = RequestActorCreationData(request.request.uri.authority, triedCreator)
                request.copy(data = creation)
              }
            }
            handleRequests(nextState)
        }

      case (_, HttpRequestSender.Stop) =>
        // This also stops all the request actors created as children of this actor.
        Behaviors.stopped
    }
}
