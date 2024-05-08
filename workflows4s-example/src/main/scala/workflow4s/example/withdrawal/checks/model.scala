package workflow4s.example.withdrawal.checks

import cats.effect.IO
import io.circe.{Codec, KeyDecoder, KeyEncoder}

sealed trait ReviewDecision // TODO how to handle extensibility? E.g. some metadata required for particular checks?
object ReviewDecision {
  case object Approve extends ReviewDecision
  case object Reject  extends ReviewDecision
}

sealed trait Decision
object Decision {
  case class RejectedBySystem()   extends Decision
  case class ApprovedBySystem()   extends Decision
  case class RejectedByOperator() extends Decision
  case class ApprovedByOperator() extends Decision
}


sealed trait CheckResult derives Codec.AsObject
object CheckResult {
  sealed trait Finished       extends CheckResult
  sealed trait Final          extends Finished
  case class Pending()        extends CheckResult
  case class Approved()       extends Final
  case class Rejected()       extends Final
  case class RequiresReview() extends Finished
  case class TimedOut()       extends Finished
}

case class CheckKey(value: String)

object CheckKey {
  given KeyEncoder[CheckKey] = KeyEncoder.encodeKeyString.contramap(_.value)
  given KeyDecoder[CheckKey] = KeyDecoder.decodeKeyString.map(CheckKey(_))
}

trait Check[-Data] {
  def key: CheckKey
  def run(data: Data): IO[CheckResult]
}

sealed trait ChecksState {
  def results: Map[CheckKey, CheckResult]

}

object ChecksState {

  sealed trait InProgress extends ChecksState

  case object Empty extends InProgress {
    override def results: Map[CheckKey, CheckResult] = Map()
  }

  case class Pending(input: ChecksInput, results: Map[CheckKey, CheckResult])          extends InProgress {
    private def finishedChecks: Map[CheckKey, CheckResult.Finished] = results.collect({ case (key, result: CheckResult.Finished) => key -> result })
    def pendingChecks: Set[CheckKey]                                = input.checks.keySet -- finishedChecks.keySet

    def addResults(newResults: Map[CheckKey, CheckResult]) = ChecksState.Pending(input, results ++ newResults)

    def asExecuted: Option[ChecksState.Executed] = {
      val finished = finishedChecks
      Option.when(finished.size == input.checks.size)(Executed(finished))
    }
  }
  case class Executed(results: Map[CheckKey, CheckResult.Finished])                    extends InProgress {
    def isRejected: Boolean     = results.exists(_._2 == CheckResult.Rejected())
    def requiresReview: Boolean = !isRejected && results.exists(x => x._2 == CheckResult.RequiresReview() || x._2 == CheckResult.TimedOut())
    def asDecided(decision: Decision): Decided = ChecksState.Decided(results, decision)
  }
  case class Decided(results: Map[CheckKey, CheckResult.Finished], decision: Decision) extends ChecksState

}

trait ChecksInput {
  type Data
  def data: Data
  def checks: Map[CheckKey, Check[Data]]
}

object ChecksInput {

  def apply[D](data0: D, checks0: List[Check[D]]): ChecksInput = new ChecksInput {
    type Data = D
    def data: Data                         = data0
    def checks: Map[CheckKey, Check[Data]] = checks0.map(x => x.key -> x).toMap
  }
}
