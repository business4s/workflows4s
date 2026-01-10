package workflows4s.example.withdrawal.checks

import workflows4s.runtime.instanceengine.Effect

/** A Check implementation that always returns a static result. Useful for testing workflows with predictable check outcomes.
  */
case class StaticCheck[F[_], T <: CheckResult](result: T)(using E: Effect[F]) extends Check[F, Any] {
  override val key: CheckKey                  = CheckKey(s"static-${result.getClass.getSimpleName}")
  override def run(data: Any): F[CheckResult] = E.pure(result)
}

object StaticCheck {

  def apply[F[_]: Effect, T <: CheckResult](result: T): StaticCheck[F, T] =
    new StaticCheck[F, T](result)

  def pending[F[_]: Effect](): StaticCheck[F, CheckResult.Pending] =
    StaticCheck(CheckResult.Pending())

  def approved[F[_]: Effect](): StaticCheck[F, CheckResult.Approved] =
    StaticCheck(CheckResult.Approved())

  def rejected[F[_]: Effect](): StaticCheck[F, CheckResult.Rejected] =
    StaticCheck(CheckResult.Rejected())
}
