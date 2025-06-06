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

package com.github.cloudfiles.core.http.factory

import com.github.cloudfiles.core.WireMockSupport.{BasicAuthFunc, TokenAuthFunc}
import com.github.cloudfiles.core.http.MultiHostExtension.RequestActorFactory
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.auth.{BasicAuthConfig, OAuthConfig, OAuthTokenData}
import com.github.cloudfiles.core.http.{HttpRequestSender, MultiHostExtension, RetryExtension, Secret}
import com.github.cloudfiles.core.{FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlPathEqualTo}
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import org.apache.pekko.util.Timeout
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
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
class HttpRequestSenderFactoryImplITSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers
  with WireMockSupport {
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
   * @return a ''Future'' with the test condition
   */
  private def checkCreationAndRequestSending(config: HttpRequestSenderConfig): Future[Assertion] = {
    val actor = HttpRequestSenderFactoryImpl.createRequestSender(testSpawner(), serverBaseUri, config)
    checkRequestSending(actor, Path)
  }

  /**
   * Checks whether the given actor can be used to send a test request to the
   * URI provided. It is expected that this request returns the test response.
   *
   * @param actor the actor to test
   * @param uri   the URI to invoke
   * @return a ''Future'' with the test assertion
   */
  private def checkRequestSending(actor: ActorRef[HttpRequestSender.HttpCommand], uri: Uri): Future[Assertion] = {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = HttpRequestSender.SendRequest(HttpRequest(uri = uri), "testRequestData", probe.ref)
    actor ! request
    val result = probe.expectMessageType[HttpRequestSender.SuccessResult]
    result.response.status should be(StatusCodes.Accepted)
    entityToString(result.response) map { content =>
      content should be(FileTestHelper.TestDataSingleLine)
    }
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
    HttpRequestSender.sendRequestSuccess(idpSender, HttpRequest(uri = TokenPath),
      requestData = this) map { result =>
      result.response.status should be(StatusCodes.OK)
    }
  }

  it should "create an HTTP sender actor with support for retrying requests that failed with status 429" in {
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
    val BaseName = "httpSenderWithRetryAfter"
    val namedActors = collection.mutable.Map.empty[String, ActorRef[_]]
    val spawner = testSpawner(namedActors)
    val config = HttpRequestSenderConfig(actorName = Some(BaseName),
      retryAfterConfig = Some(RetryAfterConfig()))

    HttpRequestSenderFactoryImpl.createRequestSender(spawner, "https://test.example.org", config)
    namedActors.keys should contain only(BaseName, BaseName + HttpRequestSenderFactoryImpl.RetryAfterName)
  }

  it should "create an HTTP sender actor with support for retrying requests" in {
    val ScenarioName = "Retry"
    val StateRetry = "ExpectRetry"
    stubFor(get(urlPathEqualTo(Path)).inScenario(ScenarioName)
      .whenScenarioStateIs(Scenario.STARTED)
      .willReturn(aResponse().withStatus(StatusCodes.InternalServerError.intValue))
      .willSetStateTo(StateRetry))
    stubFor(get(urlPathEqualTo(Path)).inScenario(ScenarioName)
      .whenScenarioStateIs(StateRetry)
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile(ResponseFile)))
    val retryConfig = RetryExtension.RetryConfig()
    val config = HttpRequestSenderConfig(retryConfig = Some(retryConfig))

    checkCreationAndRequestSending(config)
  }

  it should "derive a name for the retry extension" in {
    val BaseName = "httpSenderWithRetry"
    val namedActors = collection.mutable.Map.empty[String, ActorRef[_]]
    val spawner = testSpawner(namedActors)
    val config = HttpRequestSenderConfig(actorName = Some(BaseName),
      retryConfig = Some(RetryExtension.RetryConfig()))

    HttpRequestSenderFactoryImpl.createRequestSender(spawner, "https://test.example.org", config)
    namedActors.keys should contain only(BaseName, BaseName + HttpRequestSenderFactoryImpl.RetryName)
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
      for {
        _ <- checkRequestSending(sender, serverUri(Path))
        _ <- checkRequestSending(sender, WireMockSupport.serverUri(server, Path))
      } yield createdSenderActors.get() should be(2)
    }
  }

  it should "create an HTTP sender actor that supports multiple hosts with a name" in {
    val BaseName = "httpMultiHostSender"
    val config = HttpRequestSenderConfig(actorName = Some(BaseName))

    val sender = HttpRequestSenderFactoryImpl.createMultiHostRequestSender(testSpawner(), config)
    sender.path.toString should endWith(BaseName)
  }
}
