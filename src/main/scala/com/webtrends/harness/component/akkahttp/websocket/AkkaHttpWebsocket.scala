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

package com.webtrends.harness.component.akkahttp.websocket

import java.util.concurrent.atomic.AtomicBoolean

import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props, Status, Terminated}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.Supervision.Directive
import akka.stream.scaladsl.{Compression, Flow, Sink, Source}
import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy, Supervision}
import akka.util.ByteString
import com.webtrends.harness.component.akkahttp.routes.{AkkaHttpEndpointRegistration, AkkaHttpRequest, EndpointOptions}
import com.webtrends.harness.component.akkahttp.websocket.AkkaHttpWebsocket._
import com.webtrends.harness.logging.LoggingAdapter

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

object AkkaHttpWebsocket {
  case class CompressionType(algorithm: String, flow: Flow[ByteString, ByteString, NotUsed])
  case class WSFailure(error: Throwable)
  val supportedCompression = Map("gzip" -> Compression.gzip, "deflate" -> Compression.deflate)

  def chosenCompression(headers: Map[String, String]): Option[CompressionType] = {
    headers.map(h => h._1.toLowerCase -> h._2).get("accept-encoding").flatMap { encoding =>
      val encSet = encoding.split(",").map(_.trim).toSet
      val selected = supportedCompression.keySet.intersect(encSet).headOption
      selected.map(algo => CompressionType(algo, supportedCompression(algo)))
    }
  }
}

class AkkaHttpWebsocket[I: ClassTag, O <: Product : ClassTag, A <: Product : ClassTag](
  authHolder: A,
  textToInput: (A, TextMessage.Strict) => Future[I],
  handleInMessage: (I, WebsocketInterface[I, O, A]) => Unit,
  outputToText: O => TextMessage.Strict,
  onClose: (A, Option[I]) => Unit = {(_: A, _: Option[I]) => ()},
  errorHandler: PartialFunction[Throwable, Directive] = AkkaHttpEndpointRegistration.wsErrorDefaultHandler,
  options: EndpointOptions = EndpointOptions.default)(implicit ec: ExecutionContext, mat: Materializer) extends LoggingAdapter {
  var closed: AtomicBoolean = new AtomicBoolean(false)

  // This the the main method to route WS messages
  def websocketHandler(req: AkkaHttpRequest): Flow[Message, Message, Any] = {
    val compressFlow: Flow[Message, Message, NotUsed] = chosenCompression(req.requestHeaders) match {
      case Some(compressOpt) =>
        Flow[Message].map {
          case tx: TextMessage =>
            ByteString(tx.getStrictText)
          case bm: BinaryMessage =>
            bm.getStrictData
        }
        .via(compressOpt.flow)
        .map(tx => BinaryMessage(tx))
      case None =>
        Flow[Message]
    }

    val sActor = mat.system.actorOf(callbackActor())
    val sink =
      Flow[Message].mapAsync(1) {
        case tm: TextMessage if tm.isStrict ⇒
          tryWrap(textToInput(authHolder, TextMessage(tm.getStrictText)))
        case bm: BinaryMessage if bm.isStrict =>
          tryWrap(textToInput(authHolder, TextMessage(bm.getStrictData.utf8String)))
      }.to(Sink.actorRef(sActor, CloseSocket(), {err: Throwable => WSFailure(err)}))

    val source: Source[Message, Unit] =
      Source.actorRef[Message](completionStrategy, failureStrategy,
        30, OverflowStrategy.dropHead).mapMaterializedValue { outgoingActor =>
        sActor ! Connect(outgoingActor, authHolder)
      } via compressFlow

    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  protected def callbackActor(): Props = Props(new SocketActor())

  private def close(lastInput: Option[I]): Unit =
    if (!closed.getAndSet(true))
      onClose(authHolder, lastInput)

  private def completionStrategy: PartialFunction[Any, CompletionStrategy] = {
    case Status.Success(s: CompletionStrategy) => s
    case Status.Success(_)                     => CompletionStrategy.immediately
    case Status.Success                        => CompletionStrategy.immediately
  }

  private def failureStrategy: PartialFunction[Any, Throwable] = {
    case Status.Failure(cause) => cause
  }

  private def tryWrap(input: => Future[I]): Future[Any] =
    Try(input).recover({ case err: Throwable =>
      Future.successful(WSFailure(err))
    }).get

  case class CloseSocket() // We get this when websocket closes
  case class Connect(actorRef: ActorRef, auth: A) // Initial connection

  // Actor that exists per each open websocket and closes when the WS closes, also routes back return messages
  class SocketActor() extends Actor {
    private[websocket] var callbactor: Option[ActorRef] = None
    private[websocket] var auth: Option[A] = None
    private[websocket] var lastInput: Option[I] = None

    override def postStop(): Unit = {
      close(lastInput)
      super.postStop()
    }

    def receive: Receive = starting

    def starting: Receive = {
      case Connect(actor, authObj) =>
        callbactor = Some(actor) // Set callback actor
        auth = Some(authObj)
        context become open()
        context.watch(actor)
      case _: CloseSocket =>
        context.stop(self)
      case WSFailure(err) =>
        log.warn("Unexpected error caused websocket to close", err)
        context.stop(self)
    }

    // When becoming this, callbactor should already be set
    def open(): Receive = {
      case input: I =>
        handleInMessage(input, new WebsocketInterface[I, O, A](callbactor.get, authHolder, lastInput, outputToText, errorHandler))
        lastInput = Some(input)

      case Terminated(actor) =>
        if (callbactor.exists(_.path.equals(actor.path))) {
          log.debug(s"Linked callback actor terminated ${actor.path.name}, closing down websocket")
          context.stop(self)
        }

      case _: CloseSocket =>
        context.stop(self)

      case WSFailure(err) =>
        if (errorHandler.isDefinedAt(err)) {
          errorHandler(err) match {
            case Supervision.Stop =>
              log.info("Stopping Stream due to error that directed us to Supervision.Stop")
              context.stop(self)
            case Supervision.Resume => // Skip this event
            case Supervision.Restart => // Treat like Resume
              log.info("No support for Supervision.Restart yet, use either Resume or Stop")
          }
        } else {
          log.warn("Unexpected error caused websocket to close", err)
          context.stop(self)
        }
      case _ => // Mainly for eating the keep alive
    }
  }
}