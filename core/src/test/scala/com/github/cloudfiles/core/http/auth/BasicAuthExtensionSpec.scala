/*
 * Copyright 2020-2023 The Developers Team.
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

import akka.actor.DeadLetter
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, `Content-Type`}
import akka.http.scaladsl.model.{ContentTypes, HttpMethods, HttpRequest, Uri}
import com.github.cloudfiles.core.http.{HttpRequestSender, Secret, auth}
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

  "BasicAuthExtension" should "add an authorization header to requests" in {
    val requestActorProbe = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val resultProbe = testKit.createTestProbe[HttpRequestSender.Result]()
    val headerContent = `Content-Type`(ContentTypes.`application/json`)
    val headerAuth = Authorization(BasicHttpCredentials(User, Password))
    val httpRequest = HttpRequest(method = HttpMethods.DELETE, uri = Uri("/path/to/delete"),
      headers = List(headerContent))
    val request = HttpRequestSender.SendRequest(httpRequest, new Object, resultProbe.ref)
    val authActor = testKit.spawn(BasicAuthExtension(requestActorProbe.ref, TestAuthConfig))

    authActor ! request
    val forwardRequest = requestActorProbe.expectMessageType[HttpRequestSender.SendRequest]
    forwardRequest.data should be(request.data)
    forwardRequest.replyTo should be(request.replyTo)
    forwardRequest.request.method should be(httpRequest.method)
    forwardRequest.request.uri should be(httpRequest.uri)
    forwardRequest.request.headers should contain only(headerContent, headerAuth)
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
