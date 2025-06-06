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

import com.github.cloudfiles.core.http.ProxySupport.{ProxySelectorFunc, ProxySpec}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import org.apache.pekko.http.scaladsl.settings.ConnectionPoolSettings
import org.apache.pekko.http.scaladsl.{ClientTransport, Http, HttpExt}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.stream.{BoundedSourceQueue, QueueOfferResult}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

private object RequestQueue {
  /** The HTTPS scheme. */
  private val SchemeHttps = "https"

  /** Default port for HTTPS requests. */
  private val PortHttps = 443

  /** Default port for plain HTTP requests. */
  private val PortHttp = 80

  /**
   * Extracts the port from the specified URI. If a port is explicitly
   * provided, it is used. Otherwise the default port for the scheme is used.
   *
   * @param uri the URI
   * @return the port of this URI
   */
  def extractPort(uri: Uri): Int = {
    val port = uri.authority.port
    if (port != 0) port
    else extractPortFromScheme(uri)
  }

  /**
   * Creates a flow to execute HTTP requests to the host identified by the
   * given URI. From the URI host and port are extracted. Whether the flow is
   * for sending HTTP or HTTPS requests is determined from the URI's scheme.
   *
   * @param uri    the URI
   * @param proxy  the function to select a proxy
   * @param ext    the Pekko HTTP extension main entry point
   * @param system the actor system
   * @tparam T the type of objects passed to the flow
   * @return the flow for sending HTTP requests to this URI
   */
  def createPoolClientFlow[T](uri: Uri, proxy: ProxySelectorFunc, ext: HttpExt)
                             (implicit system: ActorSystem[_]): Flow[(HttpRequest, T),
    (Try[HttpResponse], T), Http.HostConnectionPool] = {
    val host = uri.authority.host.toString()
    val port = extractPort(uri)
    val settings = fetchSettings(uri, proxy)
    if (SchemeHttps == uri.scheme)
      ext.cachedHostConnectionPoolHttps(host, port, settings = settings)
    else ext.cachedHostConnectionPool(host, port, settings = settings)
  }

  /**
   * Obtains the setting for the HTTP flow. If configured, a special transport
   * for a proxy will be used.
   *
   * @param uri    the target URI of the flow
   * @param proxy  the proxy selector function
   * @param system the actor system
   * @return settings for the connection pool
   */
  private def fetchSettings(uri: Uri, proxy: ProxySelectorFunc)
                           (implicit system: ActorSystem[_]): ConnectionPoolSettings =
    ConnectionPoolSettings(system).withTransport(fetchClientTransport(proxy(uri)))

  /**
   * Returns a ''ClientTransport'' object depending on the availability of a
   * proxy definition.
   *
   * @param proxySettings an ''Option'' with parameters for a proxy
   * @return a ''ClientTransport'' to be used for the current setup
   */
  private def fetchClientTransport(proxySettings: Option[ProxySpec]): ClientTransport =
    proxySettings map { spec =>
      spec.credentials.fold(ClientTransport.httpsProxy(spec.address)) { credentials =>
        ClientTransport.httpsProxy(spec.address, credentials)
      }
    } getOrElse ClientTransport.TCP

  /**
   * Determines the port to be used for an URI based on its scheme.
   *
   * @param uri the URI
   * @return the port to be used for this URI
   */
  private def extractPortFromScheme(uri: Uri): Int =
    if (SchemeHttps == uri.scheme) PortHttps else PortHttp
}

/**
 * A helper class to manage a flow for sending requests to the WebDav server.
 *
 * This is based on the usage example of the Pekko HTTP host-level client API
 * from the official Pekko documentation.
 *
 * See https://pekko.apache.org/docs/pekko-http/current/client-side/host-level.html#examples
 *
 * @param uri       the URI requests are to be sent to
 * @param queueSize the size of the request queue
 * @param proxy     a function to select the proxy
 * @param system    the actor system
 */
private class RequestQueue(uri: Uri, queueSize: Int, proxy: ProxySelectorFunc)(implicit system: ActorSystem[_]) {

  import RequestQueue._

  /** The flow for generating HTTP requests. */
  val poolClientFlow: Flow[(HttpRequest, Promise[HttpResponse]),
    (Try[HttpResponse], Promise[HttpResponse]), Http.HostConnectionPool] =
    createPoolClientFlow[Promise[HttpResponse]](uri, proxy, Http())

  /** The queue acting as source for the stream of requests and a kill switch. */
  val queue: BoundedSourceQueue[(HttpRequest, Promise[HttpResponse])] =
    Source.queue[(HttpRequest, Promise[HttpResponse])](queueSize)
      .via(poolClientFlow)
      .toMat(Sink.foreach({
        case (Success(resp), p) => p.success(resp)
        case (Failure(e), p) => p.failure(e)
      }))(Keep.left)
      .run()

  /**
   * Puts a request into the queue and returns a ''Future'' with the response
   * returned from the server.
   *
   * @param request the request
   * @return a ''Future'' with the response
   */
  def queueRequest(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> responsePromise) match {
      case QueueOfferResult.Enqueued =>
        responsePromise.future
      case QueueOfferResult.Dropped =>
        Future.failed(new RuntimeException("Queue overflowed."))
      case QueueOfferResult.Failure(ex) =>
        Future.failed(ex)
      case QueueOfferResult.QueueClosed =>
        Future.failed(new RuntimeException("Queue was closed."))
    }
  }

  /**
   * Shuts down this queue by terminating the request stream. Afterwards no
   * requests can be sent any more.
   */
  def shutdown(): Unit = {
    queue.complete()
  }
}
