package com.webtrends.harness.component.akkahttp.routes

import akka.actor.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path => p, _}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives.provide
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.corsRejectionHandler
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.webtrends.harness.command.ExecuteCommand
import com.webtrends.harness.logging.Logger

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}


trait AkkaHttpParameters
trait AkkaHttpPathSegments
trait AkkaHttpAuth

// Use these to generically extract values from a query string
case class Holder1(_1: String) extends Product1[String] with AkkaHttpPathSegments
case class Holder2(_1: String, _2: String) extends Product2[String, String] with AkkaHttpPathSegments
case class Holder3(_1: String, _2: String, _3: String) extends Product3[String, String, String] with AkkaHttpPathSegments
case class Holder4(_1: String, _2: String, _3: String, _4: String)
  extends Product4[String, String, String, String] with AkkaHttpPathSegments
case class Holder5(_1: String, _2: String, _3: String, _4: String, _5: String)
  extends Product5[String, String, String, String, String] with AkkaHttpPathSegments
case class Holder6(_1: String, _2: String, _3: String, _4: String, _5: String, _6: String)
  extends Product6[String, String, String, String, String, String] with AkkaHttpPathSegments

case class AkkaHttpResponse[T](data: Option[T], statusCode: Option[StatusCode], headers: Seq[HttpHeader] = List())
case class AkkaHttpRequest(
                            path: String,
                            segments: AkkaHttpPathSegments,
                            method: HttpMethod,
                            requestHeaders: Map[String, String],
                            params: AkkaHttpParameters,
                            auth: AkkaHttpAuth,
                            queryParams: Map[String, String],
                            time: Long,
                            requestBody:Option[RequestEntity] = None
                          )

object RouteGenerator {
  // TODO: Add new rejection/exceptionHandler to recover with as new parameter
  // TODO: Verify catch all 500 match works
  def makeRoute[T <: Product : ClassTag, V](path: String,
                      method: HttpMethod,
                      defaultHeaders: Seq[HttpHeader],
                      enableCors: Boolean,
                      commandRef: ActorRef,
                      requestHandler: AkkaHttpRequest => Future[T],
                      responseHandler: V => Route)(implicit ec: ExecutionContext, log: Logger, timeout: Timeout): Route = {

    val httpPath = parseRouteSegments(path)
    httpPath { segments: AkkaHttpPathSegments =>
      respondWithHeaders(defaultHeaders: _*) {
        corsSupport(method, enableCors) {
          httpMethod(method) {
            // TODO: handleRejections and handleExceptions more part of marshaller, still here or only in marshaller now?
            httpParams { params: AkkaHttpParameters =>
              parameterMap { paramMap: Map[String, String] =>
                httpAuth { auth: AkkaHttpAuth =>
                  extractRequest { request =>
                    val reqHeaders = request.headers.map(h => h.name.toLowerCase -> h.value).toMap
                    val httpEntity =  getPayload(method, request)
                    val notABean = AkkaHttpRequest(path, segments, method, reqHeaders, params, auth, paramMap, System.currentTimeMillis(), httpEntity)
                    // http request handlers should be built with authorization in mind.
                    onComplete(for {
                      requestObjs <- requestHandler(notABean)
                      commandResult <- (commandRef ? ExecuteCommand("", requestObjs, timeout))
                    } yield responseHandler(commandResult.asInstanceOf[V])) {
                      case Success(route: Route) =>
                        route
                      case Failure(ex: Throwable) =>
                        val firstClass = ex.getStackTrace.headOption.map(_.getClassName)
                          .getOrElse(ex.getClass.getSimpleName)
                        log.warn(s"Unhandled Error [$firstClass - '${ex.getMessage}'], Wrap in an AkkaHttpException before sending back", ex)
                        complete(StatusCodes.InternalServerError, "There was an internal server error.")
                      case other =>
                        log.warn(s"$other")
                        complete(StatusCodes.InternalServerError, "Hit nothing")
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  protected[routes] def parseRouteSegments(path: String)(implicit log: Logger): Directive1[AkkaHttpPathSegments] = {
    val segs = path.split("/").filter(_.nonEmpty).toSeq
    var segCount = 0
    try {
      val dir = segs.tail.foldLeft(segs.head.asInstanceOf[Any]) { (x, y) =>
        y match {
          case s1: String if s1.startsWith("$") =>
            segCount += 1
            (x match {
              case pStr: String => if (pStr.startsWith("$")) {
                segCount += 1
                Segment / Segment
              } else pStr / Segment
              case pMatch: PathMatcher[Unit] if segCount == 1 => pMatch / Segment
              case pMatch: PathMatcher[Tuple1[String]] if segCount == 2 => pMatch / Segment
              case pMatch: PathMatcher[(String, String)] if segCount == 3 => pMatch / Segment
              case pMatch: PathMatcher[(String, String, String)] if segCount == 4 => pMatch / Segment
              case pMatch: PathMatcher[(String, String, String, String)] if segCount == 5 => pMatch / Segment
              case pMatch: PathMatcher[(String, String, String, String, String)] if segCount == 6 => pMatch / Segment

            }).asInstanceOf[PathMatcher[_]]
          case s1: String =>
            (x match {
              case pStr: String => if (pStr.startsWith("$")) {
                segCount += 1
                Segment / s1
              } else pStr / s1
              case pMatch: PathMatcher[_] => pMatch / s1
            }).asInstanceOf[PathMatcher[_]]
        }
      }

      // Create holders for any arguments on the query path
      segCount match {
        case 0 if segs.size == 1 => p(path) & provide(new AkkaHttpPathSegments {})
        case 0 => p(dir.asInstanceOf[PathMatcher[Unit]]) & provide(new AkkaHttpPathSegments {})
        case 1 => p(dir.asInstanceOf[PathMatcher[Tuple1[String]]]).as(Holder1)
        case 2 => p(dir.asInstanceOf[PathMatcher[(String, String)]]).as(Holder2)
        case 3 => p(dir.asInstanceOf[PathMatcher[(String, String, String)]]).as(Holder3)
        case 4 => p(dir.asInstanceOf[PathMatcher[(String, String, String, String)]]).as(Holder4)
        case 5 => p(dir.asInstanceOf[PathMatcher[(String, String, String, String, String)]]).as(Holder5)
        case 6 => p(dir.asInstanceOf[PathMatcher[(String, String, String, String, String, String)]]).as(Holder6)
      }
    } catch {
      case ex: Throwable =>
        log.error(s"Error adding path ${path}", ex)
        throw ex
    }
  }

  private def corsSupport(method: HttpMethod, enableCors: Boolean): Directive0 = {
    if (enableCors) {
      handleRejections(corsRejectionHandler) & CorsDirectives.cors(corsSettings(immutable.Seq(method)))
    } else {
      pass
    }
  }

  // TODO: is this method actually overriden anywhere? Is it needed?
  def httpParams: Directive1[AkkaHttpParameters] = provide(new AkkaHttpParameters {})

  // TODO: is this method actually overriden anywhere? Is it needed?
  def httpAuth: Directive1[AkkaHttpAuth] = provide(new AkkaHttpAuth {})

  def httpMethod(method: HttpMethod): Directive0 = method match {
    case HttpMethods.GET => get
    case HttpMethods.PUT => put
    case HttpMethods.POST => post
    case HttpMethods.DELETE => delete
    case HttpMethods.OPTIONS => options
    case HttpMethods.PATCH => patch
  }

  def getPayload(method: HttpMethod, request:HttpRequest):Option[RequestEntity] = method match {
    case HttpMethods.PUT | HttpMethods.POST => Some(request.entity)
    case _ => None
  }

  def corsSettings(allowedMethods: immutable.Seq[HttpMethod]): CorsSettings = CorsSettings.Default(
    CorsSettings.defaultSettings.allowGenericHttpRequests,
    CorsSettings.defaultSettings.allowCredentials,
    CorsSettings.defaultSettings.allowedOrigins,
    CorsSettings.defaultSettings.allowedHeaders,
    allowedMethods,
    CorsSettings.defaultSettings.exposedHeaders,
    CorsSettings.defaultSettings.maxAge
  )

  def entityToString(req: RequestEntity)(implicit ec: ExecutionContext, mat: Materializer): Future[String] =
    Unmarshaller.stringUnmarshaller(req)

  def entityToBytes(req: RequestEntity)(implicit ec: ExecutionContext, mat: Materializer): Future[Array[Byte]] =
    Unmarshaller.byteArrayUnmarshaller(req)
}
