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

import akka.actor.Props
import com.webtrends.harness.command.Command
import com.webtrends.harness.component.akkahttp.routes.AkkaHttpRequest

import scala.concurrent.Future

class SimpleCommandActor extends Command[AkkaHttpRequest, AkkaHttpRequest] {
  override def execute(input: AkkaHttpRequest): Future[AkkaHttpRequest] = {
    Future.successful(input)
  }
}

object SimpleCommandActor {
  def apply(): Props = Props(new SimpleCommandActor())
}


