package workflows4s.web.ui.http

import cats.effect.IO
import sttp.client4.impl.cats.FetchCatsBackend
import sttp.client4.*
import io.circe.parser.decode
import workflows4s.web.api.model.{ProgressResponse, WorkflowDefinition, WorkflowInstance}

object Http {

  private val backend = FetchCatsBackend[IO]()
  private val baseUrl = "http://localhost:8081"

  def getInstance(workflowId: String, instanceId: String): IO[WorkflowInstance] = {
    val uri = uri"$baseUrl/api/v1/definitions/$workflowId/instances/$instanceId"
    basicRequest
      .get(uri)
      .send(backend)
      .flatMap { response =>
        response.body match {
          case Right(json) =>
            IO.fromEither(decode[WorkflowInstance](json))
              .adaptError(err => new Exception(s"Failed to parse WorkflowInstance: ${err.getMessage}"))
          case Left(error) =>
            IO.raiseError(new Exception(s"HTTP Error: $error"))
        }
      }
  }

  def listDefinitions: IO[List[WorkflowDefinition]] = {
    val uri = uri"$baseUrl/api/v1/definitions"
    basicRequest
      .get(uri)
      .send(backend)
      .flatMap { response =>
        response.body match {
          case Right(json) =>
            IO.fromEither(decode[List[WorkflowDefinition]](json))
              .adaptError(err => new Exception(s"Failed to parse definitions: ${err.getMessage}"))
          case Left(error) =>
            IO.raiseError(new Exception(s"HTTP Error: $error"))
        }
      }
  }

  def getProgress(workflowId: String, instanceId: String): IO[ProgressResponse] = {
    val uri = uri"$baseUrl/api/v1/definitions/$workflowId/instances/$instanceId/progress"
    basicRequest
      .get(uri)
      .send(backend)
      .flatMap { response =>
        response.body match {
          case Right(json) =>
            IO.fromEither(decode[ProgressResponse](json))
              .adaptError(err => new Exception(s"Failed to parse progress: ${err.getMessage}"))
          case Left(error) =>
            IO.raiseError(new Exception(s"HTTP Error: $error"))
        }
      }
  }

  def getProgressAsMermaid(workflowId: String, instanceId: String): IO[String] = {
    val uri = uri"$baseUrl/api/v1/definitions/$workflowId/instances/$instanceId/progress/mermaid"
    basicRequest
      .get(uri)
      .send(backend)
      .flatMap { response =>
        response.body match {
          case Right(mermaid) => IO.pure(mermaid)
          case Left(error)    => IO.raiseError(new Exception(s"HTTP Error: $error"))
        }
      }
  }

  def createTestInstance(workflowId: String): IO[WorkflowInstance] = {
    val uri = uri"$baseUrl/api/v1/definitions/$workflowId/test-instance"
    basicRequest
      .post(uri)
      .send(backend)
      .flatMap { response =>
        response.body match {
          case Right(json) =>
            IO.fromEither(decode[WorkflowInstance](json))
              .adaptError(err => new Exception(s"Failed to parse test instance: ${err.getMessage}"))
          case Left(error) =>
            IO.raiseError(new Exception(s"HTTP Error: $error"))
        }
      }
  }
}
