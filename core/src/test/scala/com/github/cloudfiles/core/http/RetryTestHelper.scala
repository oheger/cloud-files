package com.github.cloudfiles.core.http

import com.github.cloudfiles.core.http.HttpRequestSender.{DiscardEntityMode, FailedResponseException}
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpHeader, HttpRequest, HttpResponse, ResponseEntity, StatusCode}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object RetryTestHelper {
  /** A test HTTP request sent to the test actor. */
  final val TestRequest = HttpRequest(uri = "https://example.org/foo")

  /** A data object associated with the test request. */
  final val RequestData = "someRequestData"

  /**
   * A function alias for creating the behavior of an actor to be tested based
   * on the passed in HTTP request sender actor.
   */
  type TestBehaviorCreator = ActorRef[HttpRequestSender.HttpCommand] => Behavior[HttpRequestSender.HttpCommand]

  /**
   * Creates a failed result object with a response that failed with the status
   * code provided.
   *
   * @param request the request to be answered
   * @param status  the status code
   * @param headers headers of the response
   * @param entity  an optional response entity
   * @return the resulting ''FailedResult'' object
   */
  def failedResult(request: HttpRequestSender.SendRequest, status: StatusCode,
                   headers: List[HttpHeader] = Nil, entity: ResponseEntity = HttpEntity.Empty):
  HttpRequestSender.FailedResult =
    HttpRequestSender.FailedResult(request,
      FailedResponseException(HttpResponse(status = status, headers = headers, entity = entity)))
}

/**
 * A test helper class for extension actors supporting a retry mechanism. The
 * class provides functionality to send requests, generate responses, and check
 * for results.
 *
 * @param testKit         the actor test kit
 * @param matchers        the matchers for assertions
 * @param behaviorCreator the function to create the behavior of the test actor
 */
class RetryTestHelper(testKit: ActorTestKit, matchers: Matchers)
                     (behaviorCreator: RetryTestHelper.TestBehaviorCreator) {

  import RetryTestHelper._
  import matchers._

  /** A probe acting as the underlying request actor. */
  private val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()

  /** A probe acting as the client of the request actor. */
  private val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()

  /** The extension actor to be tested. */
  private val retryExtension = testKit.spawn(behaviorCreator(probeRequest.ref))

  /**
   * Sends the given request to the actor to be tested.
   *
   * @param request the request to send
   * @return this test helper
   */
  def sendRequest(request: HttpRequestSender.SendRequest): RetryTestHelper = {
    retryExtension ! request
    this
  }

  /**
   * Sends a test request to the test extension actor.
   *
   * @param discardMode the entity discard mode to set for this request
   * @return this test helper
   */
  def sendTestRequest(discardMode: DiscardEntityMode = DiscardEntityMode.OnFailure): RetryTestHelper =
    sendRequest(HttpRequestSender.SendRequest(TestRequest, RequestData, probeReply.ref, discardMode))

  /**
   * Expects a forwarded request to be sent to the underlying request actor.
   *
   * @return the forwarded request
   */
  def expectForwardedRequest(): HttpRequestSender.SendRequest =
    probeRequest.expectMessageType[HttpRequestSender.SendRequest]

  /**
   * Expects that a request is forwarded to the underlying request actor and
   * sends a response.
   *
   * @param resultFunc a function to produce the result
   * @tparam A the type of the result
   * @return this test helper
   */
  def checkAndAnswerForwardedRequest[A <: HttpRequestSender.Result](resultFunc: HttpRequestSender.SendRequest => A):
  RetryTestHelper = {
    val fwdRequest = expectForwardedRequest()
    fwdRequest.request should be(TestRequest)
    val result = resultFunc(fwdRequest)
    fwdRequest.replyTo ! result
    this
  }

  /**
   * Expects that a successful result is delivered to the client actor. Does
   * some checks on the result.
   *
   * @return the result that was received
   */
  def expectSuccessResult(): HttpRequestSender.SuccessResult =
    checkSendRequest(probeReply.expectMessageType[HttpRequestSender.SuccessResult])

  /**
   * Expects that a failure result is delivered to the client actor. Does
   * some checks on the result.
   *
   * @return the result that was received
   */
  def expectFailureResult(): HttpRequestSender.FailedResult =
    checkSendRequest(probeReply.expectMessageType[HttpRequestSender.FailedResult])

  /**
   * Checks that no message is forwarded to the underlying request actor in a
   * specific time frame. This is used to test whether scheduling of retried
   * requests works as expected.
   *
   * @return this test helper
   */
  def expectNoForwardedRequest(): RetryTestHelper = {
    probeRequest.expectNoMessage(1500.millis)
    this
  }

  /**
   * Tests whether the actor under test correctly handles a ''Stop'' message by
   * terminating itself and forwarding to the request sender actor.
   */
  def checkHandlingOfStopMessage(): Unit = {
    val btk = BehaviorTestKit(behaviorCreator(probeRequest.ref))

    btk.run(HttpRequestSender.Stop)
    btk.returnedBehavior should be(Behaviors.stopped)
    probeRequest.expectMessage(HttpRequestSender.Stop)
  }

  /**
   * Checks whether the request contained in the given result has the
   * expected properties of the test request.
   *
   * @param result the result to check
   * @tparam A the type of the result
   * @return the same result
   */
  private def checkSendRequest[A <: HttpRequestSender.Result](result: A): A = {
    result.request.data should be(RequestData)
    result.request.request should be(TestRequest)
    result
  }
}

