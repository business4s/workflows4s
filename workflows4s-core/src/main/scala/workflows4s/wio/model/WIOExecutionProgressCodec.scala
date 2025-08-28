package workflows4s.wio.model

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax.*

object WIOExecutionProgressCodec {

  // Simplified approach - just encode ExecutedResult
  given [State: Encoder]: Encoder[WIOExecutionProgress.ExecutedResult[State]] =
    Encoder.instance { result =>
      Json.obj(
        "_status" -> (result.value match {
          case Right(_) => Json.fromString("Completed")
          case Left(_)  => Json.fromString("Failed")
        }),
        "index"   -> Json.fromInt(result.index),
        "state"   -> (result.value match {
          case Right(state) => state.asJson
          case Left(_)      => Json.Null
        }),
      )
    }

  given [State: Decoder]: Decoder[WIOExecutionProgress.ExecutedResult[State]] =
    Decoder.instance { (c: HCursor) =>
      for {
        status <- c.get[String]("_status")
        index  <- c.get[Int]("index")
        result <- status match {
                    case "Completed" => c.get[State]("state").map(st => WIOExecutionProgress.ExecutedResult(Right(st), index))
                    case "Failed"    => Right(WIOExecutionProgress.ExecutedResult(Left("Failed"), index))
                    case other       => Left(DecodingFailure(s"Unknown result status: $other", c.history))
                  }
      } yield result
    }

  // Simple encoder for ExecutionResult (Option[ExecutedResult])
  given [State: Encoder]: Encoder[WIOExecutionProgress.ExecutionResult[State]] =
    Encoder.instance {
      case None         => Json.obj("_status" -> Json.fromString("NotStarted"))
      case Some(result) => result.asJson
    }

  given [State: Decoder]: Decoder[WIOExecutionProgress.ExecutionResult[State]] =
    Decoder.instance { (c: HCursor) =>
      c.get[String]("_status").flatMap {
        case "NotStarted" => Right(None)
        case _            => c.as[WIOExecutionProgress.ExecutedResult[State]].map(Some(_))
      }
    }

}
