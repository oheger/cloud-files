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

import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.{DiscardEntityMode, FailedResponseException}
import com.github.cloudfiles.core.http.{HttpRequestSender, Secret}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorSystemOps
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.{ActorSystem, DeadLetter}
import org.apache.pekko.http.scaladsl.model.Uri.Query
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, OAuth2BearerToken, RawHeader}
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object OAuthExtensionSpec {
  /** The URI of the test token endpoint. */
  private val TokenUri = "https://test.idp.org/tokens"

  /** The string used as secret for the test OAuth client. */
  private val ClientSecret = "theSecretOfTheTestClient"

  /** Test token data. */
  private val TestTokens = OAuthTokenData(accessToken = "<access_token>", refreshToken = "<refresh_token>")

  /** OAuth configuration of the test client. */
  private val TestConfig = OAuthConfig(redirectUri = "https://redirect.uri.org/", clientID = "testClient",
    tokenEndpoint = TokenUri, clientSecret = Secret(ClientSecret), initTokenData = TestTokens)

  /** A test request used by the tests. */
  private val TestRequest = HttpRequest(method = HttpMethods.POST, uri = "http://test.org/foo",
    headers = List(RawHeader("X-Test", "yes")))

  /** Token data representing refreshed tokens. */
  private val RefreshedTokens = OAuthTokenData(accessToken = "<new_access>", refreshToken = "<new_refresh>")

  /** Test data associated with a request. */
  private val RequestData = new Object

  /**
   * A map with the expected parameters for a request to refresh the access
   * token.
   */
  private val RefreshTokenParams = Map("client_id" -> TestConfig.clientID,
    "redirect_uri" -> TestConfig.redirectUri, "client_secret" -> ClientSecret,
    "refresh_token" -> TestTokens.refreshToken, "grant_type" -> "refresh_token")

  /** The form data for a refresh token request. */
  private val RefreshData = FormData(RefreshTokenParams)

  /** The response of a request to refresh the access token. */
  private val TokenResponse =
    s"""
       |{
       |  "token_type": "bearer",
       |  "expires_in": 3600,
       |  "scope": "someScope",
       |  "access_token": "${RefreshedTokens.accessToken}",
       |  "refresh_token": "${RefreshedTokens.refreshToken}"
       |}
       |""".stripMargin

  /**
   * A data class describing an expected request and the result to be sent by
   * the stub actor. An optional delay for the response can be configured. That
   * way, the requests sent by the actor under test can be checked, and
   * arbitrary responses can be injected.
   *
   * @param expectedRequest the request, which is expected
   * @param result          the result to sent
   * @param formData        flag whether this is a form data request
   * @param optDelay        an optional delay
   * @param expDiscardMode  the expected entity discard mode
   */
  case class StubData(expectedRequest: HttpRequest,
                      result: HttpRequestSender.Result,
                      formData: Boolean = false,
                      optDelay: Option[FiniteDuration] = None,
                      expDiscardMode: DiscardEntityMode = DiscardEntityMode.OnFailure) {
    /**
     * Returns the result to return for the current request, injecting this
     * request.
     *
     * @param request the current request
     * @return the adjusted result
     */
    def adjustedResult(request: HttpRequestSender.SendRequest): HttpRequestSender.Result =
      result match {
        case res: HttpRequestSender.SuccessResult =>
          res.copy(request = request)
        case res: HttpRequestSender.FailedResult =>
          res.copy(request = request)
      }
  }

  /**
   * Creates a test request that has an authorization header.
   *
   * @param accessToken the access token expected in the header
   * @return the authorized test request
   */
  private def createAuthorizedTestRequest(accessToken: String = TestTokens.accessToken): HttpRequest = {
    val headerAuth = Authorization(OAuth2BearerToken(accessToken))
    val newHeaders = TestRequest.headers :+ headerAuth
    TestRequest.withHeaders(newHeaders)
  }

  /**
   * Convenience function to create a test ''SendRequest'' for the given probe.
   *
   * @param probe   the test probe
   * @param request the actual HTTP request
   * @return the test ''SendRequest''
   */
  private def createSendRequest(probe: TestProbe[HttpRequestSender.Result], request: HttpRequest = TestRequest):
  HttpRequestSender.SendRequest =
    HttpRequestSender.SendRequest(request, RequestData, probe.ref)

  /**
   * Generates a request to refresh an access token.
   *
   * @return the refresh request
   */
  private def refreshTokenRequest(): HttpRequest =
    HttpRequest(method = HttpMethods.POST, uri = TestConfig.tokenEndpoint, entity = RefreshData.toEntity)

  /**
   * Generates the response of a token refresh request with a new access token
   * and an optional request token.
   *
   * @return the response
   */
  private def refreshTokenResponse(): HttpResponse =
    HttpResponse(status = StatusCodes.OK, entity = TokenResponse)

  /**
   * An actor implementation that simulates an HTTP actor. This actor expects
   * ''SendRequest'' messages that must correspond to the test request. These
   * requests are answered by configurable responses. From the requests
   * received the ''Authorization'' headers and recorded and can be queried.
   */
  object HttpStubActor {
    def apply(stubs: List[StubData]): Behavior[HttpRequestSender.HttpCommand] =
      Behaviors.receivePartial {
        case (context, request: HttpRequestSender.SendRequest) =>
          context.log.info("Serving request {}.", request)
          val expected = stubs.head
          if (expected.formData) {
            handleFormDataRequests(context, request, expected)
          } else {
            if (request.request == expected.expectedRequest && request.discardEntityMode == expected.expDiscardMode) {
              sendResponse(context, request, expected)
            } else {
              val builder = new StringBuilder("Unexpected request. Expected: ")
              builder.append(expected.expectedRequest)
              builder.append(", got: ")
              builder.append(request)
              request.request.header[Authorization].foreach { auth =>
                builder.append(", Authorization: ")
                builder.append(auth.credentials.token())
              }
              throw new IllegalStateException(builder.toString())
            }
          }

          apply(stubs.tail)
      }

    /**
     * Sends a result to the client with an optional delay.
     *
     * @param context  the actor context
     * @param request  the request
     * @param stubData the object with the stub data
     */
    private def sendResponse(context: ActorContext[HttpRequestSender.HttpCommand],
                             request: HttpRequestSender.SendRequest,
                             stubData: StubData): Unit = {
      val result = stubData.adjustedResult(request)
      if (stubData.optDelay.isDefined) {
        context.scheduleOnce(stubData.optDelay.get, request.replyTo, result)
      } else {
        request.replyTo ! result
      }
    }

    /**
     * Checks a request that is expected to have a form data entity.
     *
     * @param context  the actor context
     * @param request  the incoming request
     * @param expected the stub data
     */
    private def handleFormDataRequests(context: ActorContext[HttpRequestSender.HttpCommand],
                                       request: HttpRequestSender.SendRequest, expected: StubData): Unit = {
      implicit val system: ActorSystem = context.system.toClassic
      import system.dispatcher
      (for {params1 <- parseParameters(request.request)
            params2 <- parseParameters(expected.expectedRequest)
            } yield params1 == params2) onComplete {
        case Success(true) =>
          sendResponse(context, request, expected)
        case f =>
          throw new IllegalStateException("Failed to compare request entities: " + f)
      }
    }

    /**
     * Parses the request entity, which is expected to contain form parameters
     * and returns a map with these parameters.
     *
     * @param request the request to parse
     * @param system  the actor system
     * @return a future with the parameters
     */
    private def parseParameters(request: HttpRequest)(implicit system: ActorSystem): Future[Map[String, String]] = {
      import system.dispatcher
      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      request.entity.dataBytes.runWith(sink)
        .map(bs => Query(bs.utf8String))
        .map(_.toMap)
    }
  }

}

/**
 * Test class for ''OAuthExtension''.
 */
class OAuthExtensionSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers {

  import OAuthExtensionSpec._

  /**
   * Compares ''SendRequest'' objects. As some modifications are done during
   * request processing, there cannot be a 1:1 comparison.
   *
   * @param expected the expected request
   * @param actual   the actual request
   */
  private def checkRequest(expected: HttpRequestSender.SendRequest, actual: HttpRequestSender.SendRequest): Unit = {
    actual.data should be(expected.data)
    actual.request.withHeaders(Nil) should be(expected.request.withHeaders(Nil))
  }

  "OAuthExtension" should "add the Authorization header to requests" in {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probe)
    val fwdRequest = request.copy(data = request)
    val result = HttpRequestSender.SuccessResult(fwdRequest, HttpResponse(status = StatusCodes.Accepted))
    val stubs = List(StubData(createAuthorizedTestRequest(), result))
    val helper = new ExtensionTestHelper(stubs, Nil)

    helper.sendTestRequest(request)
    val response = probe.expectMessageType[HttpRequestSender.SuccessResult]
    checkRequest(request, response.request)
    response.response should be(result.response)
  }

  it should "drop an empty Authorization header" in {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val httpRequestWithEmptyAuth = TestRequest.withHeaders(TestRequest.headers :+ AuthExtension.EmptyAuthHeader)
    val request = createSendRequest(probe, httpRequestWithEmptyAuth)
    val fwdRequest = request.copy(data = request)
    val result = HttpRequestSender.SuccessResult(fwdRequest, HttpResponse(status = StatusCodes.Accepted))
    val stubs = List(StubData(TestRequest, result))
    val helper = new ExtensionTestHelper(stubs, Nil)

    helper.sendTestRequest(request)
    val response = probe.expectMessageType[HttpRequestSender.SuccessResult]
    checkRequest(request, response.request)
    response.response should be(result.response)
  }

  it should "pass an existing Authorization header through" in {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val authHeader = Authorization(BasicHttpCredentials("scott", "tiger"))
    val httpRequestAuth = TestRequest.withHeaders(TestRequest.headers :+ authHeader)
    val request = createSendRequest(probe, httpRequestAuth)
    val fwdRequest = request.copy(data = request)
    val result = HttpRequestSender.SuccessResult(fwdRequest, HttpResponse(status = StatusCodes.OK))
    val stubs = List(StubData(httpRequestAuth, result))
    val helper = new ExtensionTestHelper(stubs, Nil)

    helper.sendTestRequest(request)
    val response = probe.expectMessageType[HttpRequestSender.SuccessResult]
    checkRequest(request, response.request)
    response.response should be(result.response)
  }

  it should "take the discard mode into account" in {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probe).copy(discardEntityMode = DiscardEntityMode.Always)
    val fwdRequest = request.copy(data = request)
    val result = HttpRequestSender.SuccessResult(fwdRequest, HttpResponse(status = StatusCodes.Accepted))
    val stubs = List(StubData(createAuthorizedTestRequest(), result, expDiscardMode = DiscardEntityMode.Always))
    val helper = new ExtensionTestHelper(stubs, Nil)

    helper.sendTestRequest(request)
    val response = probe.expectMessageType[HttpRequestSender.SuccessResult]
    response.response should be(result.response)
  }

  it should "handle a failure response from the target request actor" in {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probe)
    val fwdRequest = request.copy(data = request)
    val result = HttpRequestSender.FailedResult(fwdRequest, new IllegalStateException("Failed"))
    val stubs = List(StubData(createAuthorizedTestRequest(), result))
    val helper = new ExtensionTestHelper(stubs, Nil)

    helper.sendTestRequest(request)
    val response = probe.expectMessageType[HttpRequestSender.FailedResult]
    checkRequest(request, response.request)
    response.cause should be(result.cause)
  }

  it should "handle a response status exception from the target actor" in {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probe)
    val fwdRequest = request.copy(data = request)
    val response = HttpResponse(status = StatusCodes.BadRequest)
    val result = HttpRequestSender.FailedResult(fwdRequest, FailedResponseException(response))
    val stubs = List(StubData(createAuthorizedTestRequest(), result))
    val helper = new ExtensionTestHelper(stubs, Nil)

    helper.sendTestRequest(request)
    val failedResponse = probe.expectMessageType[HttpRequestSender.FailedResult]
    checkRequest(request, failedResponse.request)
    failedResponse.cause should be(result.cause)
  }

  /**
   * Initializes a test helper instance to expect a test request, which is
   * answered with an 401 - UNAUTHORIZED status, and a following request to
   * refresh the access token.
   *
   * @param refreshResponse the response for the refresh operation
   * @param response        the response from the wrapped request actor
   * @return the test helper and the test probe in the request
   */
  private def sendRequestWithRefresh(refreshResponse: HttpResponse, response: HttpResponse):
  (ExtensionTestHelper, TestProbe[HttpRequestSender.Result]) = {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probe)
    val authorizedRequest = createAuthorizedTestRequest()
    val fwdRequest = request.copy(data = request, request = authorizedRequest)
    val httpResponse1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response1 = HttpRequestSender.FailedResult(fwdRequest, FailedResponseException(httpResponse1))
    val response2 = HttpRequestSender.SuccessResult(fwdRequest, response)
    val refreshRequest = createSendRequest(probe, refreshTokenRequest())
    val stubRequests = List(StubData(authorizedRequest, response1),
      StubData(createAuthorizedTestRequest(RefreshedTokens.accessToken), response2))
    val refreshResult = HttpRequestSender.SuccessResult(refreshRequest, refreshResponse)
    val stubIdpRequests = List(StubData(refreshRequest.request, refreshResult, formData = true))
    val helper = new ExtensionTestHelper(stubRequests, stubIdpRequests)

    helper.sendTestRequest(request)
    (helper, probe)
  }

  it should "obtain another access token when receiving an UNAUTHORIZED response status" in {
    val httpResponse = HttpResponse(status = StatusCodes.OK)
    val (_, probe) = sendRequestWithRefresh(refreshTokenResponse(), httpResponse)

    val result = probe.expectMessageType[HttpRequestSender.SuccessResult]
    result.response should be(httpResponse)
    result.request.request.uri should be(TestRequest.uri)
  }

  it should "send a notification about a successful refresh operation" in {
    val refreshResponse = refreshTokenResponse()
    val (helper, _) = sendRequestWithRefresh(refreshResponse, HttpResponse())

    val notification = helper.nextRefreshNotification
    notification should be(Success(RefreshedTokens))
  }

  /**
   * Initializes a test helper instance to expect a test request, which is
   * answered with an 401 - UNAUTHORIZED status, and a following request to
   * refresh the access token, which again fails with the given status code.
   *
   * @param refreshStatus the status code to answer the refresh request
   * @return the test helper and the test probe in the request
   */
  private def sendRequestWithFailedRefresh(refreshStatus: StatusCode):
  (ExtensionTestHelper, TestProbe[HttpRequestSender.Result]) = {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probe)
    val authorizedRequest = createAuthorizedTestRequest()
    val fwdRequest = request.copy(data = request, request = authorizedRequest)
    val httpResponse = HttpResponse(status = StatusCodes.Unauthorized)
    val response = HttpRequestSender.FailedResult(fwdRequest, FailedResponseException(httpResponse))
    val refreshRequest = createSendRequest(probe, refreshTokenRequest())
    val stubRequests = List(StubData(authorizedRequest, response))
    val refreshResult = HttpRequestSender.FailedResult(refreshRequest.copy(data = null),
      FailedResponseException(HttpResponse(status = refreshStatus)))
    val stubIdpRequests = List(StubData(refreshRequest.request, refreshResult, formData = true))
    val helper = new ExtensionTestHelper(stubRequests, stubIdpRequests)

    helper.sendTestRequest(request)
    (helper, probe)
  }

  it should "handle a failed refresh operation" in {
    val status = StatusCodes.InternalServerError
    val (_, probe) = sendRequestWithFailedRefresh(status)

    val failedResult = probe.expectMessageType[HttpRequestSender.FailedResult]
    failedResult.request.request.uri should be(TestRequest.uri)
    failedResult.cause match {
      case FailedResponseException(response) =>
        response.status should be(status)
      case r => fail("Unexpected cause: " + r)
    }
  }

  it should "send a notification about a failed refresh operation" in {
    val status = StatusCodes.Forbidden
    val (helper, _) = sendRequestWithFailedRefresh(status)

    helper.nextRefreshNotification match {
      case Failure(exception: FailedResponseException) =>
        exception.response.status should be(status)
      case r => fail("Unexpected notification: " + r)
    }
  }

  it should "handle an unexpected response from the IDP" in {
    val probe = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probe)
    val httpResponse = HttpResponse(status = StatusCodes.Unauthorized)
    val respUnauthorized = HttpRequestSender.FailedResult(request, FailedResponseException(httpResponse))
    val stubRequests = List(StubData(createAuthorizedTestRequest(), respUnauthorized))
    val refreshRequest = createSendRequest(probe, refreshTokenRequest())
    val refreshResult = HttpRequestSender.SuccessResult(refreshRequest, HttpResponse())
    val stubIdpRequests = List(StubData(refreshRequest.request, refreshResult, formData = true))
    val helper = new ExtensionTestHelper(stubRequests, stubIdpRequests)

    helper.sendTestRequest(request)
    val result = probe.expectMessageType[HttpRequestSender.FailedResult]
    checkRequest(request, result.request)
    result.cause shouldBe a[IllegalArgumentException]
  }

  /**
   * Checks that result messages are received from the test actor (in
   * arbitrary order) that refer to the given HTTP responses.
   *
   * @param probe        the probe to receive the messages
   * @param expResponses the expected responses
   */
  private def expectResponses(probe: TestProbe[HttpRequestSender.Result], expResponses: HttpResponse*): Unit = {
    val results = (1 to expResponses.size) map (_ => probe.expectMessageType[HttpRequestSender.SuccessResult])
    results.map(_.response) should contain theSameElementsAs expResponses
  }

  it should "hold incoming requests until the access token has been refreshed" in {
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val probeIdp = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val request = createSendRequest(probeReply)
    val httpResponseUnauthorized = HttpResponse(status = StatusCodes.Unauthorized)
    val responseUnauthorized =
      HttpRequestSender.FailedResult(request, FailedResponseException(httpResponseUnauthorized))
    val response2 = HttpRequestSender.SuccessResult(request, HttpResponse())
    val response3 = HttpRequestSender.SuccessResult(request, HttpResponse(status = StatusCodes.Accepted))
    val stubRequests = List(StubData(createAuthorizedTestRequest(), responseUnauthorized),
      StubData(createAuthorizedTestRequest(RefreshedTokens.accessToken), response2),
      StubData(createAuthorizedTestRequest(RefreshedTokens.accessToken), response3))
    val helper = new ExtensionTestHelper(stubRequests, probeIdp.ref)

    helper.sendTestRequest(request)
    probeReply.expectNoMessage(100.millis)
    helper.sendTestRequest(request)
    val idpRequest = probeIdp.expectMessageType[HttpRequestSender.SendRequest]
    val idpResponse = HttpRequestSender.SuccessResult(idpRequest, refreshTokenResponse())
    idpRequest.replyTo ! idpResponse
    expectResponses(probeReply, response2.response, response3.response)
    probeIdp.expectNoMessage(100.millis)
  }

  it should "do only a single refresh operation for concurrent unauthorized requests" in {
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val probeIdp = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val request = createSendRequest(probeReply)
    val httpResponseUnauthorized = HttpResponse(status = StatusCodes.Unauthorized)
    val responseUnauthorized =
      HttpRequestSender.FailedResult(request, FailedResponseException(httpResponseUnauthorized))
    val response2 = HttpRequestSender.SuccessResult(request, HttpResponse())
    val response3 = HttpRequestSender.SuccessResult(request, HttpResponse(status = StatusCodes.Accepted))
    val stubRequests = List(
      StubData(createAuthorizedTestRequest(), responseUnauthorized, optDelay = Some(100.millis)),
      StubData(createAuthorizedTestRequest(), responseUnauthorized),
      StubData(createAuthorizedTestRequest(RefreshedTokens.accessToken), response2),
      StubData(createAuthorizedTestRequest(RefreshedTokens.accessToken), response3))
    val helper = new ExtensionTestHelper(stubRequests, probeIdp.ref)

    helper.sendTestRequest(request)
      .sendTestRequest(request)
    probeReply.expectNoMessage(100.millis)
    val idpRequest = probeIdp.expectMessageType[HttpRequestSender.SendRequest]
    val idpResponse = HttpRequestSender.SuccessResult(idpRequest, refreshTokenResponse())
    idpRequest.replyTo ! idpResponse
    expectResponses(probeReply, response2.response, response3.response)
    probeIdp.expectNoMessage(100.millis)
  }

  it should "do only a single refresh operation for concurrent unauthorized responses" in {
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val request = createSendRequest(probeReply)
    val httpResponseUnauthorized = HttpResponse(status = StatusCodes.Unauthorized)
    val responseUnauthorized =
      HttpRequestSender.FailedResult(request, FailedResponseException(httpResponseUnauthorized))
    val response2 = HttpRequestSender.SuccessResult(request, HttpResponse())
    val response3 = HttpRequestSender.SuccessResult(request, HttpResponse(status = StatusCodes.Accepted))
    val stubRequests = List(
      StubData(createAuthorizedTestRequest(), responseUnauthorized, optDelay = Some(100.millis)),
      StubData(createAuthorizedTestRequest(), responseUnauthorized, optDelay = Some(100.millis)),
      StubData(createAuthorizedTestRequest(RefreshedTokens.accessToken), response2),
      StubData(createAuthorizedTestRequest(RefreshedTokens.accessToken), response3))
    val refreshRequest = createSendRequest(probeReply, refreshTokenRequest())
    val refreshResult = HttpRequestSender.SuccessResult(refreshRequest, refreshTokenResponse())
    val stubIdpRequests = List(StubData(refreshRequest.request, refreshResult, formData = true))
    val helper = new ExtensionTestHelper(stubRequests, stubIdpRequests)

    helper.sendTestRequest(request)
      .sendTestRequest(request)
    expectResponses(probeReply, response2.response, response3.response)
  }

  it should "handle a stop request" in {
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeIdp = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val probeDeadLetters = testKit.createDeadLetterProbe()
    val deadRequest = createSendRequest(testKit.createTestProbe[HttpRequestSender.Result]())
    val actor = testKit.spawn(OAuthExtension(probeRequest.ref, probeIdp.ref, TestConfig))

    actor ! HttpRequestSender.Stop
    probeRequest.expectMessage(HttpRequestSender.Stop)
    probeIdp.expectMessage(HttpRequestSender.Stop)
    actor ! deadRequest
    val deadMsg = probeDeadLetters.expectMessageType[DeadLetter]
    deadMsg.message should be(deadRequest)
  }

  it should "handle a stop request when refreshing the access token" in {
    val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()
    val probeIdp = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val request = createSendRequest(probeReply)
    val httpResponseUnauthorized = HttpResponse(status = StatusCodes.Unauthorized)
    val responseUnauthorized =
      HttpRequestSender.FailedResult(request, FailedResponseException(httpResponseUnauthorized))
    val stubRequests = List(StubData(createAuthorizedTestRequest(), responseUnauthorized))
    val helper = new ExtensionTestHelper(stubRequests, probeIdp.ref)
    helper.sendTestRequest(request)
    probeIdp.expectMessageType[HttpRequestSender.SendRequest]

    helper.sendTestRequest(HttpRequestSender.Stop)
    probeIdp.expectMessage(HttpRequestSender.Stop)
  }

  /**
   * A test helper class managing a test actor and its dependencies. When
   * constructing an instance, the stubbing information for the decorated actor
   * and the IDP request actor has to be provided.
   *
   * @param stubRequests    stub requests for the wrapped request actor
   * @param idpRequestActor the actor to send requests to the IDP
   */
  private class ExtensionTestHelper(stubRequests: List[StubData],
                                    idpRequestActor: ActorRef[HttpRequestSender.HttpCommand]) {
    def this(stubRequests: List[StubData], stubIdpRequests: List[StubData]) =
      this(stubRequests, testKit.spawn(HttpStubActor(stubIdpRequests)))

    /** The actor simulating the request actor to be decorated. */
    private val targetRequestActor = testKit.spawn(HttpStubActor(stubRequests))

    /** A queue for recording notifications about refresh operations. */
    private val refreshQueue = new LinkedBlockingQueue[Try[OAuthTokenData]]

    /** The actor instance to be tested. */
    private val oauthActor = createOAuthActor()

    /**
     * Sends the given request to the actor under test.
     *
     * @param request the request
     * @return this test helper
     */
    def sendTestRequest(request: HttpRequestSender.HttpCommand): ExtensionTestHelper = {
      oauthActor ! request
      this
    }

    /**
     * Returns the next notification about a refresh operation or fails if
     * there is none.
     *
     * @return the next notification
     */
    def nextRefreshNotification: Try[OAuthTokenData] = {
      val notification = refreshQueue.poll(3, TimeUnit.SECONDS)
      notification should not be null
      notification
    }

    /**
     * The notification function for refresh operations. This implementation
     * stores the notification in a queue, from where it can be queried.
     *
     * @param notification the notification
     */
    private def recordRefreshNotification(notification: Try[OAuthTokenData]): Unit = {
      refreshQueue offer notification
    }

    /**
     * Creates an instance of the actor to be tested.
     *
     * @return the test actor instance
     */
    private def createOAuthActor(): ActorRef[HttpRequestSender.HttpCommand] =
      testKit.spawn(OAuthExtension(targetRequestActor, idpRequestActor,
        TestConfig.copy(refreshNotificationFunc = recordRefreshNotification)))
  }

}
