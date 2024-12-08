package workflow4s.example.checks

import scala.util.Random

import cats.effect.IO
import workflow4s.example.withdrawal.checks.{Check, CheckKey, CheckResult}

case class StaticCheck[T <: CheckResult](result: T) extends Check[Any] {
  override val key: CheckKey                   = CheckKey(Random.alphanumeric.take(10).mkString)
  override def run(data: Any): IO[CheckResult] = IO(result)
}
