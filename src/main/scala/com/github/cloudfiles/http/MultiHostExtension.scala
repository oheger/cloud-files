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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.Uri

/**
 * An actor implementation that can send HTTP requests to multiple hosts.
 *
 * Being a layer on top of Akka HTTP's host level API, [[HttpRequestSender]]
 * is configured with a single host, to which all requests are sent. This actor
 * implementation overcomes this limitation by creating request actors
 * dynamically whenever a request to a new host is encountered. All request
 * actors managed by an instance are hold in a map, so they are reused.
 *
 * Note that this implementation is not intended to be used as a generic
 * mechanism for sending arbitrary HTTP requests; keeping all the different
 * request actors around would be rather ineffective. Some protocols, however,
 * require that requests are sent to different hosts, for instance when file
 * downloads are handled by a different server than other API calls.
 */
object MultiHostExtension {
  /**
   * Creates a new instance of this actor class.
   *
   * @param requestQueueSize the size of the request queue
   * @return the initial behavior of the new actor instance
   */
  def apply(requestQueueSize: Int = HttpRequestSender.DefaultQueueSize): Behavior[HttpRequestSender.HttpCommand] =
    handleRequests(Map.empty, requestQueueSize)

  /**
   * Returns the behavior for request handling. The map passed in contains the
   * request actors currently available. When receiving a request for another
   * host, a new actor is created and added to this map.
   *
   * @param requestActors    the map with the current request actors
   * @param requestQueueSize the size of the request queue
   * @return the behavior to handle requests
   */
  private def handleRequests(requestActors: Map[Uri.Authority, ActorRef[HttpRequestSender.HttpCommand]],
                             requestQueueSize: Int): Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.receivePartial {
      case (context, request: HttpRequestSender.SendRequest) =>
        val authority = request.request.uri.authority
        val nextRequestActors = if (requestActors.contains(authority)) requestActors
        else {
          context.log.info("Creating request actor for authority {}.", authority)
          val requestActor = context.spawnAnonymous(HttpRequestSender(request.request.uri, requestQueueSize))
          requestActors + (authority -> requestActor)
        }

        nextRequestActors(authority) ! request
        handleRequests(nextRequestActors, requestQueueSize)

      case (_, HttpRequestSender.Stop) =>
        // This also stops all the request actors created as children of this actor.
        Behaviors.stopped
    }
}
