package com.webtrends.harness.component.akkahttp.websocket

import akka.actor.{ActorRef, PoisonPill}
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.Supervision
import akka.stream.Supervision.Directive
import com.webtrends.harness.logging.LoggingAdapter

import scala.reflect.ClassTag

/**
 * Interface allowing as many responses as desired to a WS input
 * @param authInfo Auth info from WS authentication steps, here to allow convenient access
 * @param lastInput Option which provides the last input 'I' we received before this current Message,
 *                  useful for resource cleanup from the last Message
 * @tparam I Type of input messages
 * @tparam O Type of output message
 * @tparam A Type of supplied Auth to the websocket
 */
class WebsocketInterface[I: ClassTag, O <: Product : ClassTag, A <: Product : ClassTag](
                                                                                         callbactor: ActorRef,
                                                                                         val authInfo: A,
                                                                                         val lastInput: Option[I],
                                                                                         outputToText: O => TextMessage,
                                                                                         errorHandler: PartialFunction[Throwable, Directive]
                                                                                       ) extends LoggingAdapter {
  /**
   * Main method to send an up to the websocket client,
   * any calls on this will bubble up one event, can be called many times
   * @param output Event to be bubbled up, will be converted to TextMessage via 'outputToText'
   */
  def reply(output: O): Unit = {
    try {
      val text = outputToText(output)
      callbactor ! text
    } catch {
      case err: Throwable if errorHandler.isDefinedAt(err) =>
        reactToError(errorHandler(err))
      case err: Throwable =>
        log.warn("Encountered error not defined in 'errorHandler', skipping event", err)
        reactToError(Supervision.Resume)
    }
  }

  /**
   * Call this to manually stop when done with this websocket, will automatically be called if connection is severed
   */
  def stop(): Unit = {
    callbactor ! PoisonPill
  }

  private def reactToError: PartialFunction[Directive, Unit] = {
    case Supervision.Stop =>
      log.info("Stopping Stream due to error that directed us to Supervision.Stop")
      stop()
    case Supervision.Resume => // Skip this event
    case Supervision.Restart => // Treat like Resume
      log.info("No support for Supervision.Restart yet, use either Resume or Stop")
  }
}
