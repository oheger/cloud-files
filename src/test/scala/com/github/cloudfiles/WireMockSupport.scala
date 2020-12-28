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

package com.github.cloudfiles

import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.client.{MappingBuilder, ResponseDefinitionBuilder}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.{BeforeAndAfterEach, Suite}

import scala.concurrent.Future

object WireMockSupport {
  /** Test user ID. */
  val UserId = "scott"

  /** Test password for user credentials. */
  val Password = "tiger"

  /**
   * Priority for default stubs. These stubs act as catch-all for requests
   * for which no specific stub has been defined.
   */
  val PriorityDefault = 10

  /** Priority for stubs for specific resources. */
  val PrioritySpecific = 1

  /**
   * Type definition of a function that applies authorization information to
   * the given mapping builder. This is used by the stubbing helper functions.
   */
  type AuthFunc = MappingBuilder => MappingBuilder

  /**
   * Constant for an authorization function that does not apply any
   * authorization information.
   */
  val NoAuthFunc: AuthFunc = identity

  /**
   * Constant for an authorization function that adds a Basic Auth header with
   * default user credentials to a request.
   */
  val BasicAuthFunc: AuthFunc = basicAuth

  /**
   * Returns an authorization function that adds an authorization header with
   * the given bearer token to a request.
   *
   * @param token the token
   * @return the authorization function applying this token
   */
  def TokenAuthFunc(token: String): AuthFunc = mappingBuilder =>
    mappingBuilder.withHeader(Authorization.name, equalTo(s"Bearer $token"))

  /**
   * Type definition of a function that can manipulate the response of a
   * stubbed request. This can be used for instance to add a response body in
   * various formats.
   */
  type ResponseFunc = ResponseDefinitionBuilder => ResponseDefinitionBuilder

  /**
   * Returns a response function that adds the given string body to a
   * response.
   *
   * @param body the body as string
   * @return the response function that adds a string body
   */
  def bodyString(body: String): ResponseFunc = builder =>
    builder.withBody(body)

  /**
   * Returns a response function that adds the given file body to a
   * response.
   *
   * @param file the file name containing the response body
   * @return the response function that adds a body file
   */
  def bodyFile(file: String): ResponseFunc = builder =>
    builder.withBodyFile(file)

  /**
   * Adds a Basic Auth header to the specified mapping builder with the
   * default user credentials.
   *
   * @param mappingBuilder the mapping builder to be extended
   * @return the updated mapping builder
   */
  private def basicAuth(mappingBuilder: MappingBuilder): MappingBuilder =
    mappingBuilder.withBasicAuth(UserId, Password)
}

/**
 * A trait that can be mixed into an integration test spec to get support for
 * a managed WireMock server.
 *
 * The trait sets up a WireMock server and starts and stops it before and
 * after each test. Some helper methods are available, e.g. to generate URIs
 * and for WebDav-specific requests (as simulating a WebDav server is the main
 * use case for this project). The companion object defines some useful
 * constants.
 */
trait WireMockSupport extends BeforeAndAfterEach {
  this: Suite =>

  import WireMockSupport._

  /** The managed WireMock server. */
  private val wireMockServer = new WireMockServer(wireMockConfig()
    .dynamicPort())

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    wireMockServer.start()
    configureFor(wireMockServer.port())
    resetAllRequests()
  }

  override protected def afterEach(): Unit = {
    wireMockServer.stop()
    super.afterEach()
  }

  /**
   * Generates an absolute URI to the managed WireMock server with the path
   * specified.
   *
   * @param path the path of the URI (should start with a slash)
   * @return the absolute URI pointing to the managed WireMock server
   */
  protected def serverUri(path: String): String =
    s"http://localhost:${wireMockServer.port()}$path"


  /**
   * Adds a wildcard stubbing that accepts all requests with the proper
   * authorization header and returns a success response.
   *
   * @param authFunc the authorization function
   */
  protected def stubSuccess(authFunc: AuthFunc = BasicAuthFunc): Unit = {
    stubFor(authFunc(any(anyUrl()).atPriority(PriorityDefault))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody("<status>OK</status>")))
  }

  /**
   * Reads the entity of the given response and converts it to a string.
   *
   * @param response the response
   * @param mat      the object to materialize streams
   * @return a ''Future'' with the string from the entity
   */
  protected def entityToString(response: HttpResponse)(implicit mat: Materializer): Future[String] = {
    import mat.executionContext
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    response.entity.dataBytes.runWith(sink).map(_.utf8String)
  }
}
