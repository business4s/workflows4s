package workflows4s.example.withdrawal.checks

import io.circe.{Codec, Encoder, KeyDecoder, KeyEncoder}
import sttp.tapir.Schema
import workflows4s.runtime.instanceengine.Effect

sealed trait ReviewDecision derives Codec.AsObject, Schema
object ReviewDecision {
  case object Approve extends ReviewDecision
  case object Reject  extends ReviewDecision
}

sealed trait Decision derives Codec.AsObject
object Decision {
  case class RejectedBySystem()   extends Decision
  case class ApprovedBySystem()   extends Decision
  case class RejectedByOperator() extends Decision
  case class ApprovedByOperator() extends Decision
}

sealed trait CheckResult derives Codec.AsObject
object CheckResult {
  sealed trait Finished extends CheckResult derives Codec.AsObject
  sealed trait Final    extends Finished
  case class Pending()  extends CheckResult
  case class Approved() extends Final

  case class Rejected()       extends Final
  case class RequiresReview() extends Finished
  case class TimedOut()       extends Finished
}

case class CheckKey(value: String)

object CheckKey {
  given KeyEncoder[CheckKey] = KeyEncoder.encodeKeyString.contramap(_.value)
  given KeyDecoder[CheckKey] = KeyDecoder.decodeKeyString.map(CheckKey(_))
}

trait Check[F[_], -Data] {
  def key: CheckKey
  def run(data: Data): F[CheckResult]
}

/** Helper for creating static checks that always return the same result, useful for tests.
  */
case class StaticCheck[F[_]](result: CheckResult)(using F: Effect[F]) extends Check[F, Any] {
  def key: CheckKey                  = CheckKey(s"static-${result.toString}")
  def run(data: Any): F[CheckResult] = F.pure(result)
}

sealed trait ChecksState derives Encoder {
  def results: Map[CheckKey, CheckResult]
}

object ChecksState {

  sealed trait InProgress extends ChecksState derives Encoder

  case object Empty extends InProgress {
    override def results: Map[CheckKey, CheckResult] = Map()
  }

  case class Pending(input: ChecksInput[?], results: Map[CheckKey, CheckResult])       extends InProgress {
    private def finishedChecks: Map[CheckKey, CheckResult.Finished] = results.collect({ case (key, result: CheckResult.Finished) => key -> result })
    def pendingChecks: Set[CheckKey]                                = input.checks.keySet -- finishedChecks.keySet

    def addResults(newResults: Map[CheckKey, CheckResult]) = ChecksState.Pending(input, results ++ newResults)

    def asExecuted: Option[ChecksState.Executed] = {
      val finished = finishedChecks
      Option.when(finished.size == input.checks.size)(Executed(finished))
    }
  }
  case class Executed(results: Map[CheckKey, CheckResult.Finished])                    extends InProgress {
    def isRejected: Boolean                    = results.exists(_._2 == CheckResult.Rejected())
    def requiresReview: Boolean                = !isRejected && results.exists(x => x._2 == CheckResult.RequiresReview() || x._2 == CheckResult.TimedOut())
    def asDecided(decision: Decision): Decided = ChecksState.Decided(results, decision)
  }
  case class Decided(results: Map[CheckKey, CheckResult.Finished], decision: Decision) extends ChecksState derives Encoder

}

trait ChecksInput[F[_]] {
  type Data
  def data: Data
  def checks: Map[CheckKey, Check[F, Data]]
}

object ChecksInput {

  def apply[F0[_], D](data0: D, checks0: List[Check[F0, D]]): ChecksInput[F0] = new ChecksInput[F0] {
    type Data = D
    def data: Data                             = data0
    def checks: Map[CheckKey, Check[F0, Data]] = checks0.map(x => x.key -> x).toMap
  }

  given [F[_]]: Encoder[ChecksInput[F]] = Encoder.instance { input =>
    import io.circe.syntax.*
    io.circe.Json.obj(
      "checks" -> input.checks.keys.map(_.value).asJson,
    )
  }

}
