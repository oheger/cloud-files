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

package com.github.cloudfiles.core.http.factory

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import akka.util.Timeout
import com.github.cloudfiles.core.WireMockSupport.{BasicAuthFunc, TokenAuthFunc}
import com.github.cloudfiles.core.http.MultiHostExtension.RequestActorFactory
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.auth.{BasicAuthConfig, OAuthConfig, OAuthTokenData}
import com.github.cloudfiles.core.http.{HttpRequestSender, MultiHostExtension, Secret}
import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlPathEqualTo}
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

object HttpRequestSenderFactoryImplITSpec {
  /** The path for sending test requests. */
  private val Path = "/request"

  /** The name of the file with the response of the test request. */
  private val ResponseFile = "response.txt"

  /** A timeout for querying the actor under test. */
  private implicit val RequestTimeout: Timeout = Timeout(3.seconds)
}

/**
 * Test class for ''HttpRequestSenderFactoryImpl''.
 */
class HttpRequestSenderFactoryImplITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with WireMockSupport with AsyncTestHelper {
  override protected val resourceRoot: String = "core"

  import HttpRequestSenderFactoryImplITSpec._

  /**
   * Returns a ''Spawner'' that uses the actor testkit to spawn new actors.
   * Optionally, a map can be provided, in which newly created named actors are
   * stored.
   *
   * @param namedActors a map where to store named actors
   * @return the ''Spawner'' for this test class
   */
  private def testSpawner(namedActors: collection.mutable.Map[String, ActorRef[_]] = collection.mutable.Map.empty):
  Spawner = new Spawner {
    override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] = {
      props should be(Props.empty)
      optName match {
        case Some(name) =>
          val ref = testKit.spawn(behavior, name)
          namedActors += (name -> ref)
          ref
        case None =>
          testKit.spawn(behavior)
      }
    }
  }

  /**
   * Creates a new test actor instance based on the passed in configuration
   * and tests whether it is correctly initialized. This is done by sending a
   * test request and checking whether the expected response is received.
   *
   * @param config the configuration for the test actor
   * @return the actor created for the test
   */
  private def checkCreationAndRequestSending(config: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand] = {
    val actor = HttpRequestSenderFactoryImpl.createRequestSender(testSpawner(), serverBaseUri, config)
    checkRequestSending(actor, Path)
    actor
  }

  /**
   * Checks whether the given actor can be used to send a test request to the
   * URI provided. It is expected that this request returns the test response.
   *
   * @param actor the actor to test
   * @param uri   the URI to invoke
   */
  private def checkRequestSending(actor: ActorRef[HttpRequestSender.HttpCommand], uri: Uri): Unit = {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = uri), "testRequestData", probe.ref)
    actor ! request
    val result = probe.expectMessageType[HttpRequestSender.SuccessResult]
    result.response.status should be(StatusCodes.Accepted)
    val content = futureResult(entityToString(result.response))
    content should be(FileTestHelper.TestDataSingleLine)
  }

  "HttpRequestSenderFactoryImpl" should "create a basic HTTP sender actor" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile(ResponseFile)))

    checkCreationAndRequestSending(HttpRequestSenderConfig())
  }

  it should "create a basic HTTP sender actor with a specific name" in {
    val ActorName = "basicHttpSenderActor"
    val config = HttpRequestSenderConfig(actorName = Some(ActorName))

    val sender = HttpRequestSenderFactoryImpl.createRequestSender(testSpawner(), serverBaseUri, config)
    sender.path.toString should endWith(ActorName)
  }

  it should "create an HTTP sender actor with support for Basic Auth" in {
    stubFor(BasicAuthFunc(get(urlPathEqualTo(Path)))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile(ResponseFile)))
    val authConfig = BasicAuthConfig(WireMockSupport.UserId, Secret(WireMockSupport.Password))
    val config = HttpRequestSenderConfig(authConfig = authConfig)

    checkCreationAndRequestSending(config)
  }

  it should "derive a name for the Basic Auth actor" in {
    val BaseName = "httpSenderWithBasicAuth"
    val namedActors = collection.mutable.Map.empty[String, ActorRef[_]]
    val spawner = testSpawner(namedActors)
    val baseActor = HttpRequestSenderFactoryImpl.createRequestSender(spawner, serverBaseUri,
      HttpRequestSenderConfig())
    namedActors should have size 0

    val authConfig = BasicAuthConfig(WireMockSupport.UserId, Secret(WireMockSupport.Password))
    val config = HttpRequestSenderConfig(authConfig = authConfig, actorName = Some(BaseName))
    HttpRequestSenderFactoryImpl.decorateRequestSender(spawner, baseActor, config)
    namedActors.keys should contain only (BaseName + HttpRequestSenderFactoryImpl.BasicAuthName)
  }

  it should "create an HTTP sender actor with support for OAuth tokens" in {
    val Token = "MyAccessToken"
    stubFor(TokenAuthFunc(Token)(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile(ResponseFile))))
    val authConfig = OAuthConfig(serverUri("/token"), "someRedirect", "someClient",
      Secret("someSecret"), OAuthTokenData(Token, "someRefreshToken"))
    val config = HttpRequestSenderConfig(authConfig = authConfig)

    checkCreationAndRequestSending(config)
  }

  it should "construct a correct request sender for the IDP to support token refresh operations" in {
    val TokenPath = "/token"
    val BaseName = "httpSenderWithOAuth"
    stubFor(get(urlPathEqualTo(TokenPath))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val authConfig = OAuthConfig(serverUri(TokenPath), "someRedirect", "someClient",
      Secret("someSecret"), OAuthTokenData("someAccessToken", "someRefreshToken"))
    val namedActors = collection.mutable.Map.empty[String, ActorRef[_]]
    val spawner = testSpawner(namedActors)
    val config = HttpRequestSenderConfig(authConfig = authConfig, actorName = Some(BaseName))

    HttpRequestSenderFactoryImpl.createRequestSender(spawner, "https://test.example.org", config)
    namedActors.keys should contain only(BaseName, BaseName + HttpRequestSenderFactoryImpl.OAuthName,
      BaseName + HttpRequestSenderFactoryImpl.IDPName)
    val idpSender = namedActors(BaseName + HttpRequestSenderFactoryImpl.IDPName)
      .asInstanceOf[ActorRef[HttpRequestSender.HttpCommand]]
    val result = futureResult(HttpRequestSender.sendRequestSuccess(idpSender, HttpRequest(uri = TokenPath), this))
    result.response.status should be(StatusCodes.OK)
  }

  it should "create an HTTP sender actor with support for retrying requests" in {
    val ScenarioName = "Retry"
    val StateRetry = "ExpectRetry"
    stubFor(get(urlPathEqualTo(Path)).inScenario(ScenarioName)
      .whenScenarioStateIs(Scenario.STARTED)
      .willReturn(aResponse().withStatus(StatusCodes.TooManyRequests.intValue))
      .willSetStateTo(StateRetry))
    stubFor(get(urlPathEqualTo(Path)).inScenario(ScenarioName)
      .whenScenarioStateIs(StateRetry)
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile(ResponseFile)))
    val config = HttpRequestSenderConfig(retryAfterConfig = Some(RetryAfterConfig()))

    checkCreationAndRequestSending(config)
  }

  it should "derive a name for the retry after extension" in {
    val BaseName = "httpSenderWithRetry"
    val namedActors = collection.mutable.Map.empty[String, ActorRef[_]]
    val spawner = testSpawner(namedActors)
    val config = HttpRequestSenderConfig(actorName = Some(BaseName),
      retryAfterConfig = Some(RetryAfterConfig()))

    HttpRequestSenderFactoryImpl.createRequestSender(spawner, "https://test.example.org", config)
    namedActors.keys should contain only(BaseName, BaseName + HttpRequestSenderFactoryImpl.RetryAfterName)
  }

  it should "create an HTTP sender actor that supports multiple hosts" in {
    stubFor(BasicAuthFunc(get(urlPathEqualTo(Path)))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile(ResponseFile)))
    val authConfig = BasicAuthConfig(WireMockSupport.UserId, Secret(WireMockSupport.Password))
    val config = HttpRequestSenderConfig(authConfig = authConfig)
    val createdSenderActors = new AtomicInteger
    val factory: RequestActorFactory = (uri, size, proxy) => {
      createdSenderActors.incrementAndGet()
      MultiHostExtension.defaultRequestActorFactory(uri, size, proxy)
    }

    runWithNewServer { server =>
      server.stubFor(BasicAuthFunc(get(urlPathEqualTo(Path)))
        .willReturn(aResponse()
          .withStatus(StatusCodes.Accepted.intValue)
          .withBodyFile(ResponseFile)))

      val sender = HttpRequestSenderFactoryImpl.createMultiHostRequestSender(testSpawner(), config, factory)
      checkRequestSending(sender, serverUri(Path))
      checkRequestSending(sender, WireMockSupport.serverUri(server, Path))
      createdSenderActors.get() should be(2)
    }
  }

  it should "create an HTTP sender actor that supports multiple hosts with a name" in {
    val BaseName = "httpMultiHostSender"
    val config = HttpRequestSenderConfig(actorName = Some(BaseName))

    val sender = HttpRequestSenderFactoryImpl.createMultiHostRequestSender(testSpawner(), config)
    sender.path.toString should endWith(BaseName)
  }
}
