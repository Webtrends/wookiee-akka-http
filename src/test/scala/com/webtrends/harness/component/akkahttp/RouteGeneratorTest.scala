/*
 *  Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.webtrends.harness.component.akkahttp

import java.util.Locale

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.PredefinedToEntityMarshallers
import akka.http.scaladsl.model.{HttpMethods, _}
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.HttpHeader.ParsingResult.{Error, Ok}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.Timeout
import com.webtrends.harness.command.CommandFactory
import com.webtrends.harness.component.akkahttp.routes.{AkkaHttpRequest, RouteGenerator}
import com.webtrends.harness.component.akkahttp.util.{Forbidden, NotAuthorized, RequestInfo}
import com.webtrends.harness.component.akkahttp.util.TestJsonSupport._
import com.webtrends.harness.logging.Logger
import org.scalatest.WordSpec

import scala.concurrent.Future
import scala.concurrent.duration._

class RouteGeneratorTest extends WordSpec with ScalatestRouteTest with PredefinedToEntityMarshallers {

  implicit val actorSystem: ActorSystem = ActorSystem("test")
  implicit val timeout = Timeout(2 seconds)
  implicit val logger: Logger = Logger.getLogger(getClass.getName)


  import RouteGeneratorTest._

  def actorRef = actorSystem.actorOf(CommandFactory.createCommand(simpleFunction))
  def messageActorRef = actorSystem.actorOf(CommandFactory.createCommand(messageFunction))
  def exceptionActorRef = actorSystem.actorOf(CommandFactory.createCommand(exceptionFunction))

  "RouteGenerator " should {

    "add simple route" in {
      val r = RouteGenerator.makeHttpRoute("getTest", HttpMethods.GET, actorRef, requestHandler, responseHandler200, rejectionHandler)
      Get("/getTest") ~> r ~> check {
        assert(status == StatusCodes.OK)
        assert(entityAs[String] contains "getTest")
      }
    }
    "route with path segments" in {
      val r = RouteGenerator.makeHttpRoute("getTest/$id", HttpMethods.GET, actorRef, requestHandler, responseHandler200, rejectionHandler)
      Get("/getTest/123") ~> r ~> check {
        assert(status == StatusCodes.OK)
        assert(entityAs[RequestInfo].segments == List("123"))
      }
    }
    "route with path segments and query params" in {
      val r = RouteGenerator.makeHttpRoute("getTest/$id", HttpMethods.GET, actorRef, requestHandler, responseHandler200, rejectionHandler)
      Get("/getTest/123?enable=true") ~> r ~> check {
        assert(status == StatusCodes.OK)
        assert(entityAs[RequestInfo].segments == List("123"))
        assert(entityAs[RequestInfo].queryParams == Map("enable" -> "true"))
      }
    }
    "route with post method" in {
      val r = RouteGenerator.makeHttpRoute("postTest", HttpMethods.POST, actorRef, requestHandler, responseHandler200, rejectionHandler)
      Post("/postTest") ~> r ~> check {
        assert(status == StatusCodes.OK)
        assert(entityAs[RequestInfo].verb == "HttpMethod(POST)")
      }
    }
    "providing accessLog id getter gets called with AkkaHttpRequest object" in {
      var called = false
      val r = RouteGenerator.makeHttpRoute("accessLogTest", HttpMethods.GET, actorRef, requestHandler,
        responseHandler200, rejectionHandler, Some(r => {
          called = r.isInstanceOf[AkkaHttpRequest];
          "works"
        }))

      Get("/accessLogTest") ~> r ~> check {
        assert(called, "called variable not reset by accessLogIdGetter call")
      }
    }

    "response should include defaults headers" in {
      val userHeader = HttpHeader.parse("userId", "system") match {
        case Ok(header, _) => Some(header)
        case Error(_) => None
      }
      val r = RouteGenerator.makeHttpRoute("getTest", HttpMethods.GET, actorRef, requestHandler, responseHandler200, rejectionHandler,
        defaultHeaders = Seq(userHeader.get))

      Get("/getTest") ~> r ~> check {
        assert(status == StatusCodes.OK)
        assert(headers.contains(userHeader.get))
      }
    }
  }

  "Request Handler" should {
    "process authentication logic" in {
      var isAuthenticated = false
      val r = RouteGenerator.makeHttpRoute("authTest", HttpMethods.GET, actorRef, r => {isAuthenticated = true;Future.successful(r)},
        responseHandler200, rejectionHandler)

      Get("/authTest") ~> r ~> check {
        assert(status == StatusCodes.OK)
        assert(isAuthenticated, "authentication not called")
      }
    }
    "route with authentication failure returned in request handler throw 401 error code" in {
      val r = RouteGenerator.makeHttpRoute("errorTest", HttpMethods.GET, actorRef, requestHandlerWithAuthenticationFailure, responseHandler200, rejectionHandler)
      Get("/errorTest") ~> r ~> check {
        assert(status == StatusCodes.Unauthorized)
        assert(entityAs[String] contains failMessage)
      }
    }
    "route with unknown failure returned in request handler throw 500 error code " in {
      val r = RouteGenerator.makeHttpRoute("errorTest", HttpMethods.GET, actorRef, requestHandlerWithUnknownFailure, responseHandler200, rejectionHandler)
      Get("/errorTest") ~> r ~> check {
        assert(status == StatusCodes.InternalServerError)
        assert(entityAs[String] contains failMessage)
      }
    }
    "route with exception in request handler (abnormal termination of logic) throw 500 error code " in {
      val r = RouteGenerator.makeHttpRoute("errorTest", HttpMethods.GET, actorRef, requestHandlerWithException, responseHandler200, rejectionHandler)
      Get("/errorTest") ~> r ~> check {
        assert(status == StatusCodes.InternalServerError)
        // exceptions are caught by default exception handler of Akka Http
        assert(entityAs[String] contains StatusCodes.InternalServerError.defaultMessage)
      }
    }
  }

  "Response Handler" should {
    "successfully transform output of the business logic into Akka Http Route" in {
      val r = RouteGenerator.makeHttpRoute("account/$accountGuid/report/$reportId", HttpMethods.GET, actorRef, requestHandler, responseHandler200, rejectionHandler)
      Get("/account/abc/report/123") ~> r ~> check {
        assert(status == StatusCodes.OK)
        assert(entityAs[RequestInfo].segments == List("abc", "123"))
      }
    }
    "route with error in response handler" in {
      val r = RouteGenerator.makeHttpRoute("errorTest", HttpMethods.GET, actorRef, requestHandler, errorOnResponse, rejectionHandler)
      Get("/errorTest") ~> r ~> check {
        assert(status == StatusCodes.InternalServerError)
        assert(entityAs[String] contains failMessage)
      }
    }
    "actorRef output and response handler input should of same type, otherwise throw cast exception" in {
      val r = RouteGenerator.makeHttpRoute("errorTest", HttpMethods.GET, messageActorRef, requestHandler, responseHandler200, rejectionHandler)
      Get("/errorTest") ~> r ~> check {
        assert(status == StatusCodes.InternalServerError)
        assert(entityAs[String] contains "cannot be cast to")
      }
    }
  }

  "Rejection Handler" should {
    "catch failure in the request handler " in {
      val r = RouteGenerator.makeHttpRoute("authTest", HttpMethods.GET, actorRef,
        r => {
          Future.failed(Forbidden(failMessage))
        },
        responseHandler200, rejectionHandler)

      Get("/authTest") ~> r ~> check {
        assert(status == StatusCodes.Forbidden)
        assert(entityAs[String] contains failMessage)
      }
    }
    "must caught Failure in business logic(actorRef)" in {
      val r = RouteGenerator.makeHttpRoute("errorTest", HttpMethods.GET, exceptionActorRef, requestHandler, responseHandler200, rejectionHandler)
      Get("/errorTest") ~> r ~> check {
        assert(status == StatusCodes.InternalServerError)
        assert(entityAs[String] contains failMessage)
      }
    }
    "If an exception is not handle in rejection handler then it must be caught at onComplete Failure case" in {
      val r = RouteGenerator.makeHttpRoute("errorTest", HttpMethods.GET, exceptionActorRef, requestHandler, responseHandler200, rejectionHandlerWithLimitedScope)
      Get("/errorTest") ~> r ~> check {
        assert(status == StatusCodes.InternalServerError)
        assert(entityAs[String] contains StatusCodes.InternalServerError.defaultMessage)
      }
    }
  }

  "Request Locales" should {
    "accept-language header with multiple languages" in {
      val locales = RouteGenerator.requestLocales(Map("accept-language" -> "ru;q=0.9, de, en;q=0.7"))
      assert(locales.size == 3)
      assert(locales(0) == Locale.forLanguageTag("de"))
      assert(locales(1) == Locale.forLanguageTag("ru"))
      assert(locales(2) == Locale.forLanguageTag("en"))
    }
    "accept-language header with empty string" in {
      val locales = RouteGenerator.requestLocales(Map("accept-language" -> ""))
      assert(locales.isEmpty)
    }
    "requestLocales with out accept-language header" in {
      val locales = RouteGenerator.requestLocales(Map.empty)
      assert(locales.isEmpty)
    }
  }
}

object RouteGeneratorTest {

  val failMessage = "purposeful fail"
  def simpleFunction(in: AkkaHttpRequest): Future[RequestInfo] =
    Future.successful(RequestInfo(in.path, in.method.toString, in.requestHeaders, in.segments, in.queryParams, None))
  def exceptionFunction(in: AkkaHttpRequest): Future[RequestInfo] = Future.failed(new Exception(failMessage))
  def messageFunction(in: AkkaHttpRequest): Future[String] = Future.successful("Welcome to Wookiee-Akka-Http")

  def requestHandler(req: AkkaHttpRequest): Future[AkkaHttpRequest] = Future.successful(req)
  def responseHandler200(resp: RequestInfo): Route = complete(StatusCodes.OK, resp)
  def rejectionHandler(request:AkkaHttpRequest): PartialFunction[Throwable, Route] = {
    case ex: NotAuthorized => complete(StatusCodes.Unauthorized, ex.message)
    case ex: Forbidden => complete(StatusCodes.Forbidden, ex.message)
    case t: Throwable => complete(StatusCodes.InternalServerError, t.getMessage)
  }
  def errorOnResponse(echoed: RequestInfo): Route = {
    if (true) throw new Exception(failMessage)
    complete(StatusCodes.InternalServerError, "should not have returned route")
  }

  def requestHandlerWithException(req: AkkaHttpRequest): Future[AkkaHttpRequest] = throw new Exception(failMessage)
  def requestHandlerWithAuthenticationFailure(req: AkkaHttpRequest): Future[AkkaHttpRequest] = Future.failed(NotAuthorized(failMessage))
  def requestHandlerWithUnknownFailure(req: AkkaHttpRequest): Future[AkkaHttpRequest] = Future.failed(new IllegalArgumentException(failMessage))
  def rejectionHandlerWithLimitedScope(request: AkkaHttpRequest): PartialFunction[Throwable, Route] = {
    case ex: NotAuthorized => complete(StatusCodes.Unauthorized, ex.message)
    case ex: Forbidden => complete(StatusCodes.Forbidden, ex.message)
  }
}
