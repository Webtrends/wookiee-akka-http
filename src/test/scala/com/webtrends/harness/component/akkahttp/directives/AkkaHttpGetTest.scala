package com.webtrends.harness.component.akkahttp.directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.webtrends.harness.command.{BaseCommandResponse, CommandBean, CommandResponse}
import com.webtrends.harness.component.akkahttp.methods.AkkaHttpGet
import com.webtrends.harness.component.akkahttp.util.TestBaseCommand
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FunSuite, MustMatchers}

import scala.concurrent.Future


class AkkaHttpGetTest extends FunSuite with PropertyChecks with MustMatchers with ScalatestRouteTest {

  test("should return InternalServerError when command fails") {
    var routes = Set.empty[Route]

    new AkkaHttpGet with TestBaseCommand {
      override def path: String = "test"
      override def addRoute(r: Route): Unit = routes += r
      override def execute[T : Manifest](bean: Option[CommandBean]): Future[BaseCommandResponse[T]] =
        Future.failed(new Exception("error"))
    }

    Get("/test") ~> routes.reduceLeft(_ ~ _) ~> check {
      status mustEqual StatusCodes.InternalServerError
    }
  }

  test("should respond with NoContent when command respond with no data") {
    var routes = Set.empty[Route]

    new AkkaHttpGet with TestBaseCommand {
      override def path: String = "test"
      override def addRoute(r: Route): Unit = routes += r
      override def execute[T : Manifest](bean: Option[CommandBean]): Future[BaseCommandResponse[T]] =
        Future.successful(CommandResponse(None))
    }

    Get("/test") ~> routes.reduceLeft(_ ~ _) ~> check {
      status mustEqual StatusCodes.NoContent
    }
  }

}
