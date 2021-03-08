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

package com.github.cloudfiles.core

import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.{HttpResponse, StatusCode, StatusCodes}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.client.{MappingBuilder, ResponseDefinitionBuilder}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

import scala.concurrent.Future

object WireMockSupport {
  /** Test user ID. */
  final val UserId = "scott"

  /** Test password for user credentials. */
  final val Password = "tiger"

  /**
   * Priority for default stubs. These stubs act as catch-all for requests
   * for which no specific stub has been defined.
   */
  final val PriorityDefault = 10

  /** Priority for stubs for specific resources. */
  final val PrioritySpecific = 1

  /** Constant for the content-type header. */
  final val HeaderContentType = "Content-Type"

  /** The content type for a JSON entity. */
  final val ContentTypeJson = "application/json"

  /**
   * Type definition of a function that applies authorization information to
   * the given mapping builder. This is used by the stubbing helper functions.
   */
  type AuthFunc = MappingBuilder => MappingBuilder

  /**
   * Constant for an authorization function that does not apply any
   * authorization information.
   */
  final val NoAuthFunc: AuthFunc = identity

  /**
   * Constant for an authorization function that adds a Basic Auth header with
   * default user credentials to a request.
   */
  final val BasicAuthFunc: AuthFunc = basicAuth

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
   * Generates an absolute URI to the given WireMock server with the path
   * specified.
   *
   * @param server the target server
   * @param path   the path of the URI (should start with a slash)
   * @return the absolute URI pointing to the managed WireMock server
   */
  def serverUri(server: WireMockServer, path: String): String =
    s"http://localhost:${server.port()}$path"

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
trait WireMockSupport extends BeforeAndAfterEach with BeforeAndAfterAll {
  this: Suite =>

  import WireMockSupport._

  /**
   * A property defining the project root directory. This is needed to
   * correctly determine the location of test resource files. This is a
   * work-around for the problem that Wiremock cannot find the files to serve
   * in a multi-project setup.
   */
  protected val resourceRoot: String

  /** The managed WireMock server. */
  private var wireMockServer: WireMockServer = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    wireMockServer = new WireMockServer(wireMockConfig()
      .dynamicPort()
      .withRootDirectory(s"$resourceRoot/src/test/resources"))
    wireMockServer.start()
    configureFor(wireMockServer.port())
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset()
  }

  override protected def afterAll(): Unit = {
    wireMockServer.stop()
    super.afterAll()
  }

  /**
   * Generates an absolute URI to the managed WireMock server with the path
   * specified.
   *
   * @param path the path of the URI (should start with a slash)
   * @return the absolute URI pointing to the managed WireMock server
   */
  protected def serverUri(path: String): String = WireMockSupport.serverUri(wireMockServer, path)

  /**
   * Returns an absolute URI to the root path of the managed WireMock server.
   *
   * @return the absolute root URI of the managed WireMock server
   */
  protected def serverBaseUri: String = serverUri("")

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
   * A convenience function to create an initialized builder for a JSON
   * response. The builder is initialized with the JSON content type.
   *
   * @param status the status code of the response
   * @return the initialized builder for the response
   */
  protected def aJsonResponse(status: StatusCode = StatusCodes.OK): ResponseDefinitionBuilder =
    aResponse()
      .withStatus(status.intValue())
      .withHeader(HeaderContentType, ContentTypeJson)

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

  /**
   * Support running a block of code that requires another mock server. This
   * can be needed for instance to check correct redirect handling, e.g. if the
   * API server refers to a different server for downloading files. The
   * function starts a new server and passes it to the provided ''run''
   * function. Afterwards, the server is stopped again.
   *
   * @param run the function to run with the new server
   * @tparam A the result type of the function
   * @return the result returned by the function
   */
  protected def runWithNewServer[A](run: WireMockServer => A): A = {
    val server = new WireMockServer(wireMockConfig()
      .dynamicPort()
      .withRootDirectory(s"$resourceRoot/src/test/resources"))
    server.start()
    try {
      run(server)
    } finally {
      server.stop()
    }
  }
}
