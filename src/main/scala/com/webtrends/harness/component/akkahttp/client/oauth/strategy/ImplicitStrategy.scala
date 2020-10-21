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

package com.webtrends.harness.component.akkahttp.client.oauth.strategy

import akka.NotUsed
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.stream.scaladsl.Source
import com.webtrends.harness.component.akkahttp.client.oauth.config.ConfigLike
import com.webtrends.harness.component.akkahttp.client.oauth.token.GrantType

class ImplicitStrategy extends Strategy(GrantType.Implicit) {
  override def getAuthorizeUrl(config: ConfigLike, params: Map[String, String] = Map.empty): Option[Uri] = {
    val uri = Uri
      .apply(config.getSchemaAndHost)
      .withPath(Uri.Path(config.authorizeUrl))
      .withQuery(Uri.Query(params ++ Map("response_type" -> "token", "client_id" -> config.clientId)))

    Option(uri)
  }

  override def getAccessTokenSource(config: ConfigLike, params: Map[String, String] = Map.empty, headers: Map[String, String] = Map.empty): Source[HttpRequest, NotUsed] =
    Source.empty
}
