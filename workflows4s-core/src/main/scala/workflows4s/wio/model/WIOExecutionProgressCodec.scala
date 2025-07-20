package workflows4s.wio.model

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

import scala.annotation.nowarn

object WIOExecutionProgressCodec {

  // This encoder is for a separate sealed trait used within Interruptible.
  implicit def interruptionEncoder[State]: Encoder[WIOExecutionProgress.Interruption[State]] =
    Encoder.instance {
      case WIOExecutionProgress.HandleSignal(meta, _) =>
        Json.obj("type" -> Json.fromString("Signal"), "meta" -> meta.asJson)
      case WIOExecutionProgress.Timer(meta, _) =>
        Json.obj("type" -> Json.fromString("Timer"), "meta" -> meta.asJson)
    }

  // The main encoder for the recursive WIOExecutionProgress structure.
  implicit def executionProgressEncoder[State]: Encoder[WIOExecutionProgress[State]] = Encoder.recursive { _ =>
    // We explicitly type `progress` to guide the compiler's type inference.
    Encoder.instance { (progress: WIOExecutionProgress[State]) =>
      val baseJson = Json.obj(
        "_type" -> Json.fromString(progress.getClass.getSimpleName.replace("$", "")),
        "isExecuted" -> Json.fromBoolean(progress.isExecuted),
        "result" -> progress.result.map {
          case Left(err)    => Json.fromString(s"Error: ${err.toString}")
          case Right(state) => Json.fromString(state.toString)
        }.getOrElse(Json.Null),
        "model" -> progress.toModel.asJson,
      )

      // The `@nowarn` annotation suppresses the "unchecked" warnings for the pattern match.
      @nowarn("msg=the type test for .* cannot be checked at runtime")
      val specificJson = progress match {
        case seq: WIOExecutionProgress.Sequence[State] =>
          Json.obj("steps" -> seq.steps.asJson)
        case dyn: WIOExecutionProgress.Dynamic =>
          Json.obj("meta" -> dyn.meta.asJson)
        case runIO: WIOExecutionProgress.RunIO[State] =>
          Json.obj("meta" -> runIO.meta.asJson)
        case signal: WIOExecutionProgress.HandleSignal[State] =>
          Json.obj("meta" -> signal.meta.asJson)
        case error: WIOExecutionProgress.HandleError[State] =>
          Json.obj(
            "base" -> error.base.asJson,
            "handler" -> error.handler.asJson,
            "meta" -> error.meta.asJson,
          )
        case end: WIOExecutionProgress.End[State] =>
          Json.obj()
        case pure: WIOExecutionProgress.Pure[State] =>
          Json.obj("meta" -> pure.meta.asJson)
        case loop: WIOExecutionProgress.Loop[State] =>
          Json.obj(
            "base" -> loop.base.asJson,
            "onRestart" -> loop.onRestart.asJson,
            "meta" -> loop.meta.asJson,
            "history" -> loop.history.asJson,
          )
        case fork: WIOExecutionProgress.Fork[State] =>
          Json.obj(
            "branches" -> fork.branches.asJson,
            "meta" -> fork.meta.asJson,
            "selected" -> fork.selected.asJson,
          )
        case interruptible: WIOExecutionProgress.Interruptible[State] =>
          Json.obj(
            "base" -> interruptible.base.asJson,
            "trigger" -> interruptible.trigger.asJson,
            "handler" -> interruptible.handler.asJson,
          )
        case timer: WIOExecutionProgress.Timer[State] =>
          Json.obj("meta" -> timer.meta.asJson)
        case parallel: WIOExecutionProgress.Parallel[State] =>
          Json.obj("elements" -> parallel.elements.asJson)
        case checkpoint: WIOExecutionProgress.Checkpoint[State] =>
          Json.obj("base" -> checkpoint.base.asJson)
        case recovery: WIOExecutionProgress.Recovery[State] =>
          Json.obj()
      }

      baseJson.deepMerge(specificJson)
    }
  }

  // A basic decoder is needed for the frontend client, even if it's not fully implemented.
  implicit def executionProgressDecoder[State]: Decoder[WIOExecutionProgress[State]] =
    Decoder.failedWithMessage("WIOExecutionProgress decoding is not implemented.")
}