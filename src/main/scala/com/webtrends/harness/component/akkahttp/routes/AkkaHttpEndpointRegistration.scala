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

package com.webtrends.harness.component.akkahttp.routes

import akka.http.scaladsl.model.{HttpHeader, HttpMethod}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.webtrends.harness.command.CommandHelper
import com.webtrends.harness.logging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object EndpointType extends Enumeration {
  type EndpointType = Value
  val INTERNAL, EXTERNAL, WEBSOCKET = Value
}

trait AkkaHttpEndpointRegistration {
  this: CommandHelper =>

  // TODO: new prop for enableHealthcheck?
  def addAkkaHttpEndpoint[T <: Product: ClassTag, U: ClassTag](name: String,
                                                               path: String,
                                                               method: HttpMethod,
                                                               endpointType: EndpointType.EndpointType,
                                                               requestHandler: AkkaHttpRequest => Future[T],
                                                               businessLogic: T => Future[U],
                                                               responseHandler: U => Route,
                                                               rejectionHandler: PartialFunction[Throwable, Route],
                                                               accessLogIdGetter: Option[AkkaHttpRequest => String] = None,
                                                               enableCors: Boolean = false,
                                                               defaultHeaders: Seq[HttpHeader] = Seq.empty[HttpHeader]
                                                              )(implicit ec: ExecutionContext, log: Logger, to: Timeout): Unit = {
      addCommand(name, businessLogic).map { ref =>
        val route = RouteGenerator
          .makeHttpRoute(path, method, ref, requestHandler, responseHandler, rejectionHandler, accessLogIdGetter, enableCors, defaultHeaders)

        endpointType match {
          case EndpointType.INTERNAL =>
            InternalAkkaHttpRouteContainer.addRoute(route)
          case EndpointType.EXTERNAL =>
            ExternalAkkaHttpRouteContainer.addRoute(route)
          case EndpointType.WEBSOCKET =>
            // TODO: not needed yet
            ???
        }
      }
    }
}

