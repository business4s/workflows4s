package workflows4s.wio.model

import io.circe.{Codec, Decoder, Encoder, HCursor, DecodingFailure, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import workflows4s.wio.model.WIOExecutionProgress.*

object WIOExecutionProgressCodec {

  // Match WIOModel: use "_type" discriminator
  given Configuration = Configuration.default.withDiscriminator("_type")

  // Explicit codecs for ExecutionResult since it's an alias using Any on the Left
  // We never decode the error value; we only keep "Failed" status.
  private given [State: Encoder]: Encoder[ExecutionResult[State]] =
    Encoder.instance {
      case None              => Json.obj("_status" -> Json.fromString("NotStarted"))
      case Some(Right(st))   => Json.obj("_status" -> Json.fromString("Completed"), "state" -> st.asJson)
      case Some(Left(_any))  => Json.obj("_status" -> Json.fromString("Failed"))
    }

  private given [State: Decoder]: Decoder[ExecutionResult[State]] =
    Decoder.instance { (c: HCursor) =>
      c.get[String]("_status").flatMap {
        case "NotStarted" => Right(None)
        case "Completed"  => c.get[State]("state").map(st => Some(Right(st)))
        case "Failed"     => Right(Some(Left(()))) // we ignore actual error payload
        case other        => Left(DecodingFailure(s"Unknown result status: $other", c.history))
      }
    }

  // Interruption ADT (used as a field type)
  given [State: Encoder: Decoder]: Codec.AsObject[Interruption[State]] =
    ConfiguredCodec.derived

  // Full progress ADT
  given [State: Encoder: Decoder]: Codec.AsObject[WIOExecutionProgress[State]] =
    ConfiguredCodec.derived
}