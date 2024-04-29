package workflow4s.example.testuitls

import cats.effect.IO
import workflow4s.runtime.RunningWorkflow.UnexpectedSignal
import workflow4s.wio.simple.SimpleActor

object TestUtils {
  implicit class SimpleSignalResponseOps[Resp](value: SimpleActor.SignalResponse[Resp]) {
    def extract: Resp = value match {
      case SimpleActor.SignalResponse.Ok(result)             => result
      case SimpleActor.SignalResponse.UnexpectedSignal(desc) => throw new IllegalArgumentException(s"Unexpected signal: $desc")
    }
  }

  implicit class SignalResponseOps[Resp](value: IO[Either[UnexpectedSignal, Resp]]) {
    import cats.effect.unsafe.implicits.global
    def extract: Resp = value.unsafeRunSync() match {
      case Right(result)             => result
      case Left(unexpected) => throw new IllegalArgumentException(s"Unexpected signal: ${unexpected.signalDef}")
    }
  }
}
