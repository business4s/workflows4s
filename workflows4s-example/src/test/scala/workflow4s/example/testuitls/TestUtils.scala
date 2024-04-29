package workflow4s.example.testuitls

import cats.effect.IO
import workflow4s.runtime.RunningWorkflow.UnexpectedSignal

object TestUtils {

  implicit class SignalResponseIOOps[Resp](value: IO[Either[UnexpectedSignal, Resp]]) {
    import cats.effect.unsafe.implicits.global
    def extract: Resp = value.unsafeRunSync().extract
  }
  implicit class SignalResponseOps[Resp](value: Either[UnexpectedSignal, Resp]) {
    def extract: Resp = value match {
      case Right(result)             => result
      case Left(unexpected) => throw new IllegalArgumentException(s"Unexpected signal: ${unexpected.signalDef}")
    }
  }
}
