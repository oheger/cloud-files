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

package com.github.cloudfiles.core.http.auth

import com.github.cloudfiles.core.http.{HttpRequestSender, Secret, auth}
import org.apache.pekko.actor.DeadLetter
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, RawHeader}
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object BasicAuthExtensionSpec {
  /** A test user name. */
  private val User = "testUser"

  /** A test password. */
  private val Password = "testPassword"

  /** A test configuration for basic auth. */
  private val TestAuthConfig = BasicAuthConfig(User, Secret(Password))
}

/**
 * Test class for ''BasicAuthExtension''.
 */
class BasicAuthExtensionSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers {

  import BasicAuthExtensionSpec._

  /**
   * Checks the properties of the actual received request against the expected
   * ones.
   * @param expected the expected request
   * @param actual the actual request
   * @return the actual request if comparison was successful
   */
  private def checkForwardRequest(expected: HttpRequestSender.SendRequest,
                                  actual: HttpRequestSender.SendRequest): HttpRequestSender.SendRequest = {
    actual.data should be(expected.data)
    actual.replyTo should be(expected.replyTo)
    actual.request.method should be(expected.request.method)
    actual.request.uri should be(expected.request.uri)
    actual
  }

  "BasicAuthExtension" should "add an authorization header to requests" in {
    val requestActorProbe = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val resultProbe = testKit.createTestProbe[HttpRequestSender.Result]()
    val headerTest = RawHeader("foo", "bar")
    val headerAuth = Authorization(BasicHttpCredentials(User, Password))
    val httpRequest = HttpRequest(method = HttpMethods.DELETE, uri = Uri("/path/to/delete"),
      headers = List(headerTest))
    val request = HttpRequestSender.SendRequest(httpRequest, new Object, resultProbe.ref)
    val authActor = testKit.spawn(BasicAuthExtension(requestActorProbe.ref, TestAuthConfig))

    authActor ! request

    val forwardRequest = checkForwardRequest(request,
      requestActorProbe.expectMessageType[HttpRequestSender.SendRequest])
    forwardRequest.request.headers should contain only(headerTest, headerAuth)
  }

  it should "keep an existing defined authorization header" in {
    val requestActorProbe = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val resultProbe = testKit.createTestProbe[HttpRequestSender.Result]()
    val headerTest = RawHeader("foo", "bar")
    val headerAuth = Authorization(BasicHttpCredentials("other" + User, Password + "2"))
    val httpRequest = HttpRequest(method = HttpMethods.POST, uri = Uri("/path/to/post"),
      headers = List(headerTest, headerAuth))
    val request = HttpRequestSender.SendRequest(httpRequest, new Object, resultProbe.ref)
    val authActor = testKit.spawn(BasicAuthExtension(requestActorProbe.ref, TestAuthConfig))

    authActor ! request
    val forwardRequest = checkForwardRequest(request,
      requestActorProbe.expectMessageType[HttpRequestSender.SendRequest])
    forwardRequest.request.headers should contain only(headerTest, headerAuth)
  }

  it should "drop an empty authorization header from the request" in {
    val requestActorProbe = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val resultProbe = testKit.createTestProbe[HttpRequestSender.Result]()
    val headerTest = RawHeader("foo", "bar")
    val headerAuth = RawHeader("authorization", "")
    val httpRequest = HttpRequest(method = HttpMethods.PUT, uri = Uri("/path/to/put"),
      headers = List(headerTest, headerAuth))
    val request = HttpRequestSender.SendRequest(httpRequest, new Object, resultProbe.ref)
    val authActor = testKit.spawn(BasicAuthExtension(requestActorProbe.ref, TestAuthConfig))

    authActor ! request
    val forwardRequest = checkForwardRequest(request,
      requestActorProbe.expectMessageType[HttpRequestSender.SendRequest])
    forwardRequest.request.headers should contain only headerTest
  }

  it should "stop the request actor when it is stopped" in {
    val requestActorProbe = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val deadLetterProbe = testKit.createDeadLetterProbe()
    val resultProbe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = Uri("https://test.org")), "test", resultProbe.ref)
    val authActor = testKit.spawn(auth.BasicAuthExtension(requestActorProbe.ref, TestAuthConfig))

    authActor ! HttpRequestSender.Stop
    requestActorProbe.expectMessage(HttpRequestSender.Stop)
    authActor ! request
    val deadLetter = deadLetterProbe.expectMessageType[DeadLetter]
    deadLetter.message should be(request)
  }
}
