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

package com.github.cloudfiles.core.http.auth

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.{FormData, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.github.cloudfiles.core.http.HttpRequestSender.{FailedResponseException, SendRequest}
import com.github.cloudfiles.core.http.HttpRequestSender

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * An actor implementation supporting an OAuth flow when interacting with an
 * HTTP server.
 *
 * This actor class wraps an [[com.github.cloudfiles.core.http.HttpRequestSender]]
 * actor and adds a bearer token obtained from an OAuth identity provider to
 * HTTP requests. The actor is configured with the parameters of an OAuth
 * identity provider. When a request arrives, an authorization header with the
 * current access token is added before the request is forwarded to the actual
 * HTTP request actor. If the response has status code 401 (indicating that the
 * access token is no longer valid), a request to refresh the token is sent to
 * the IDP. The access token is then updated.
 *
 * Token refresh operations and their outcome can be monitored, so that clients
 * can take additional actions, e.g. store a new access token for later reuse.
 */
object OAuthExtension {
  /** Parameter for the client ID. */
  private val ParamClientId = "client_id"

  /** Parameter for the client secret. */
  private val ParamClientSecret = "client_secret"

  /** Parameter for the redirect URI. */
  private val ParamRedirectUri = "redirect_uri"

  /** Parameter for the refresh token. */
  private val ParamRefreshToken = "refresh_token"

  /** Parameter for the grant type. */
  private val ParamGrantType = "grant_type"

  /** Constant for the refresh token grant type. */
  private val GrantTypeRefreshToken = "refresh_token"

  /**
   * An internal data class that contains the tokens extracted from the
   * response of a refresh token request.
   *
   * @param tokens a ''Try'' with the tokens extracted
   */
  private case class ExtractTokenResult(tokens: Try[OAuthTokenData])

  /**
   * Creates a new actor instance with the parameters provided.
   *
   * @param requestSender    the ''HttpRequestSender'' to decorate
   * @param idpRequestSender an ''HttpRequestSender'' to contact the IDP
   * @param oauthConfig      the configuration of the IDP
   *                         refreshed
   * @return the behavior of the new actor instance
   */
  def apply(requestSender: ActorRef[HttpRequestSender.HttpCommand],
            idpRequestSender: ActorRef[HttpRequestSender.HttpCommand],
            oauthConfig: OAuthConfig):
  Behavior[HttpRequestSender.HttpCommand] =
    handleRequests(requestSender, idpRequestSender, oauthConfig, oauthConfig.initTokenData)

  /**
   * The behavior function for normal request processing. It adds the current
   * access token to incoming requests. As long as this token remains valid,
   * requests can be served. On receiving an UNAUTHORIZED response, a token
   * refresh operation needs to be triggered.
   *
   * @param requestSender    the ''HttpRequestSender'' to decorate
   * @param idpRequestSender an ''HttpRequestSender'' to contact the IDP
   * @param oauthConfig      the configuration of the IDP
   * @param currentTokens    the current token data
   * @return the behavior to handle requests in normal mode
   */
  private def handleRequests(requestSender: ActorRef[HttpRequestSender.HttpCommand],
                             idpRequestSender: ActorRef[HttpRequestSender.HttpCommand],
                             oauthConfig: OAuthConfig,
                             currentTokens: OAuthTokenData):
  Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.receive { (context, message) =>
      (message: @unchecked) match {
        case request: SendRequest =>
          val authorizedHttpRequest = addAuthorization(request.request, currentTokens.accessToken)
          val authorizedRequest = request.copy(request = authorizedHttpRequest)
          HttpRequestSender.forwardRequest(context, requestSender, authorizedHttpRequest, authorizedRequest)
          Behaviors.same

        case HttpRequestSender.ForwardedResult(HttpRequestSender.FailedResult(SendRequest(request,
        data: SendRequest, _, _), exception: FailedResponseException))
          if exception.response.status == StatusCodes.Unauthorized =>
          if (usesCurrentToken(request, currentTokens.accessToken)) {
            refreshing(requestSender, idpRequestSender, oauthConfig, currentTokens, List(data))
          } else {
            context.self ! data
            Behaviors.same
          }

        case HttpRequestSender.ForwardedResult(result) =>
          propagateResult(result)

        case HttpRequestSender.Stop =>
          handleStop(requestSender, idpRequestSender)
      }
    }

  /**
   * Triggers a request to refresh the access token and returns a behavior that
   * handles the outcome of this operation.
   *
   * @param requestSender    the ''HttpRequestSender'' to decorate
   * @param idpRequestSender an ''HttpRequestSender'' to contact the IDP
   * @param oauthConfig      the configuration of the IDP
   * @param currentTokens    the current token data
   * @param pendingRequests  a list with requests waiting for the new token
   * @return the behavior while refreshing the token
   */
  private def refreshing(requestSender: ActorRef[HttpRequestSender.HttpCommand],
                         idpRequestSender: ActorRef[HttpRequestSender.HttpCommand],
                         oauthConfig: OAuthConfig,
                         currentTokens: OAuthTokenData,
                         pendingRequests: List[SendRequest]): Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.setup { context =>
      context.log.info("Obtaining a new access token.")
      HttpRequestSender.forwardRequest(context, idpRequestSender,
        refreshTokenRequest(oauthConfig, currentTokens.refreshToken), null)

      refreshBehavior(requestSender, idpRequestSender, oauthConfig, currentTokens, pendingRequests)
    }

  /**
   * The behavior function to handle a token refresh operation. New incoming
   * requests are parked until the refresh operation is complete.
   *
   * @param requestSender    the ''HttpRequestSender'' to decorate
   * @param idpRequestSender an ''HttpRequestSender'' to contact the IDP
   * @param oauthConfig      the configuration of the IDP
   * @param currentTokens    the current token data
   * @param pendingRequests  a list with requests waiting for the new token
   * @return the behavior while refreshing the token
   */
  private def refreshBehavior(requestSender: ActorRef[HttpRequestSender.HttpCommand],
                              idpRequestSender: ActorRef[HttpRequestSender.HttpCommand],
                              oauthConfig: OAuthConfig,
                              currentTokens: OAuthTokenData,
                              pendingRequests: List[SendRequest]): Behaviors.Receive[HttpRequestSender.HttpCommand] = {
    Behaviors.receive { (context, message) =>
      (message: @unchecked) match {
        case request: SendRequest =>
          context.log.info("Queuing request until token refresh is complete.")
          refreshBehavior(requestSender, idpRequestSender, oauthConfig, currentTokens,
            request :: pendingRequests)

        case HttpRequestSender.ForwardedResult(HttpRequestSender.SuccessResult(SendRequest(_, null, _, _), response)) =>
          extractNewTokens(context, response, currentTokens)

        case HttpRequestSender.ForwardedResult(HttpRequestSender.FailedResult(SendRequest(_, null, _, _), cause)) =>
          context.log.error("Got failed response for refresh token request.", cause)
          handleFailedTokenRefresh(requestSender, idpRequestSender, oauthConfig, currentTokens, pendingRequests, cause)

        case HttpRequestSender.ForwardedResult(HttpRequestSender.SuccessResult(SendRequest(_,
        tokens: ExtractTokenResult, _, _), _)) =>
          tokens.tokens match {
            case Failure(exception) =>
              context.log.error("Could not parse refresh token response from IDP.", exception)
              handleFailedTokenRefresh(requestSender, idpRequestSender, oauthConfig, currentTokens, pendingRequests,
                exception)
            case Success(newTokens) =>
              context.log.info("Successfully refreshed access token.")
              updateTokens(context, requestSender, idpRequestSender, oauthConfig, pendingRequests, newTokens)
          }

        case HttpRequestSender.ForwardedResult(HttpRequestSender.FailedResult(SendRequest(_,
        data: SendRequest, _, _), exception: FailedResponseException))
          if exception.response.status == StatusCodes.Unauthorized =>
          refreshBehavior(requestSender, idpRequestSender, oauthConfig, currentTokens, data :: pendingRequests)

        case HttpRequestSender.ForwardedResult(result) =>
          propagateResult(result)

        case HttpRequestSender.Stop =>
          handleStop(requestSender, idpRequestSender)
      }
    }
  }

  /**
   * Handles a result message from the decorated actor. The original request is
   * restored in the result, then it is propagated to the caller.
   *
   * @param result the result from the decorated actor
   * @return the next behavior
   */
  private def propagateResult(result: HttpRequestSender.Result): Behavior[HttpRequestSender.HttpCommand] = {
    val response = HttpRequestSender.resultFromForwardedRequest(result)
    response.request.replyTo ! response
    Behaviors.same
  }

  /**
   * Adds an ''Authorization'' header with the currently valid access token to
   * the given request.
   *
   * @param request the request
   * @param token   the current access token
   * @return the updated request
   */
  private def addAuthorization(request: HttpRequest, token: String): HttpRequest = {
    val orgHeaders = request.headers.filterNot(_.is("authorization"))
    val auth = Authorization(OAuth2BearerToken(token))
    request.withHeaders(auth :: orgHeaders.toList)
  }

  /**
   * Checks whether the given request has the current access token set in its
   * ''Authorization'' header. If this is the case, and the request failed
   * with status 401, this means that the access token has probably expired
   * and must be refreshed. If, however, the access token has been changed
   * since the request was sent, the new one should be valid. In this case,
   * the request should just be retried with the new token.
   *
   * @param request the request to be checked
   * @param token   the current access token
   * @return '''true''' if the request uses the current access token;
   *         '''false''' otherwise
   */
  private def usesCurrentToken(request: HttpRequest, token: String): Boolean =
    request.header[Authorization].exists(_.credentials.token() == token)

  /**
   * Generates a request to refresh the current access token.
   *
   * @param config       the configuration of the IDP
   * @param refreshToken the current refresh token
   * @return the request to refresh the access token
   */
  private def refreshTokenRequest(config: OAuthConfig, refreshToken: String): HttpRequest = {
    val params = Map(ParamClientId -> config.clientID, ParamRedirectUri -> config.redirectUri,
      ParamClientSecret -> config.clientSecret.secret, ParamRefreshToken -> refreshToken,
      ParamGrantType -> GrantTypeRefreshToken)
    HttpRequest(uri = config.tokenEndpoint, method = HttpMethods.POST, entity = FormData(params).toEntity)
  }

  /**
   * Extracts the text content from the given response.
   *
   * @param response the HTTP response
   * @param ec       the execution context
   * @param system   the actor system
   * @return the text content of the response
   */
  private def responseBody(response: HttpResponse)
                          (implicit ec: ExecutionContext, system: ActorSystem): Future[String] = {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    response.entity.dataBytes.runWith(sink).map(_.utf8String)
  }

  /**
   * Parses the response of a refresh token request. The result is sent in a
   * new ''ForwardedResult'' message to this actor.
   *
   * @param context  the actor context
   * @param response the response from the IDP
   * @param tokens   the current tokens
   * @return the next behavior
   */
  private def extractNewTokens(context: ActorContext[HttpRequestSender.HttpCommand], response: HttpResponse,
                               tokens: OAuthTokenData): Behavior[HttpRequestSender.HttpCommand] = {
    implicit val classicSystem: ActorSystem = context.system.toClassic
    import classicSystem.dispatcher
    val futTokens = responseBody(response) flatMap { body =>
      Future.fromTry(OAuthTokenData.fromJson(body, tokens.refreshToken))
    }
    context.pipeToSelf(futTokens) { triedTokens =>
      val request = SendRequest(HttpRequest(), ExtractTokenResult(triedTokens), null)
      HttpRequestSender.ForwardedResult(HttpRequestSender.SuccessResult(request, response))
    }
    Behaviors.same
  }

  /**
   * Performs all necessary steps to update the tokens after new ones have been
   * received from the IDP. This includes invoking the notification function
   * and dealing with requests waiting for the token update.
   *
   * @param context          the actor context
   * @param requestSender    the request sender actor
   * @param idpRequestSender an ''HttpRequestSender'' to contact the IDP
   * @param oauthConfig      the configuration of the IDP
   * @param pendingRequests  the list with pending requests
   * @param newTokens        the object with updated token data
   * @return the next behavior
   */
  private def updateTokens(context: ActorContext[HttpRequestSender.HttpCommand],
                           requestSender: ActorRef[HttpRequestSender.HttpCommand],
                           idpRequestSender: ActorRef[HttpRequestSender.HttpCommand],
                           oauthConfig: OAuthConfig,
                           pendingRequests: List[SendRequest],
                           newTokens: OAuthTokenData): Behavior[HttpRequestSender.HttpCommand] = {
    pendingRequests foreach { pr =>
      context.self ! pr
    }
    oauthConfig.refreshNotificationFunc(Success(newTokens))
    handleRequests(requestSender, idpRequestSender, oauthConfig, newTokens)
  }

  /**
   * Handles a failed token refresh operation. All pending requests are sent a
   * failure response, and the notification function is invoked. Then next
   * state is again the normal request handling behavior.
   *
   * @param requestSender    the ''HttpRequestSender'' to decorate
   * @param idpRequestSender an ''HttpRequestSender'' to contact the IDP
   * @param oauthConfig      the configuration of the IDP
   * @param currentTokens    the current token data
   * @param pendingRequests  the list with pending requests
   * @param cause            the cause for the failure
   * @return the next behavior
   */
  private def handleFailedTokenRefresh(requestSender: ActorRef[HttpRequestSender.HttpCommand],
                                       idpRequestSender: ActorRef[HttpRequestSender.HttpCommand],
                                       oauthConfig: OAuthConfig,
                                       currentTokens: OAuthTokenData,
                                       pendingRequests: List[SendRequest],
                                       cause: Throwable): Behavior[HttpRequestSender.HttpCommand] = {
    pendingRequests foreach { pr =>
      val result = HttpRequestSender.FailedResult(pr, cause)
      pr.replyTo ! result
    }
    oauthConfig.refreshNotificationFunc(Failure(cause))
    handleRequests(requestSender, idpRequestSender, oauthConfig, currentTokens)
  }

  /**
   * Handles the ''Stop'' message. Stops the actors this actor interacts with.
   *
   * @param requestSender    the underlying request sender actor
   * @param idpRequestSender the actor to contact the IDP
   * @return the next behavior
   */
  private def handleStop(requestSender: ActorRef[HttpRequestSender.HttpCommand],
                         idpRequestSender: ActorRef[HttpRequestSender.HttpCommand]):
  Behavior[HttpRequestSender.HttpCommand] = {
    requestSender ! HttpRequestSender.Stop
    idpRequestSender ! HttpRequestSender.Stop
    Behaviors.stopped
  }
}
