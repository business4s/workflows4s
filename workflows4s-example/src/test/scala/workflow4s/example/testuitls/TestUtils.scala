package workflow4s.example.testuitls

import cats.effect.IO
import workflow4s.runtime.WorkflowInstance.UnexpectedSignal

object TestUtils {

  import cats.effect.unsafe.implicits.global
  extension [Resp](value: IO[Either[UnexpectedSignal, Resp]]) {
    def extract: Resp = value.unsafeRunSync().extract
  }
  extension [Resp](value: Either[UnexpectedSignal, Resp]) {
    def extract: Resp = value match {
      case Right(result)    => result
      case Left(unexpected) => throw new IllegalArgumentException(s"Unexpected signal: ${unexpected.signalDef}")
    }
  }
}
