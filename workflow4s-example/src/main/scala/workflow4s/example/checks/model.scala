package workflow4s.example.checks

import cats.effect.IO
import workflow4s.wio.SignalDef

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

sealed trait CheckResult
object CheckResult {
  sealed trait Final          extends CheckResult
  case class Approved()       extends Final
  case class Rejected()       extends Final
  case class Pending()        extends CheckResult
  case class RequiresReview() extends CheckResult
}

case class CheckKey(value: String)
trait Check[Data] {
  val key: CheckKey
  def run(data: Data): IO[CheckResult]
}

case class ChecksState(results: Map[CheckKey, CheckResult]) {
  def finished: Set[CheckKey] = results.flatMap({ case (key, result) =>
    result match {
      case value: CheckResult.Final     => Some(key)
      case CheckResult.Pending()        => None
      case CheckResult.RequiresReview() => Some(key)
    }
  }).toSet

  def addResults(newResults: Map[CheckKey, CheckResult]) = ChecksState(results ++ newResults)

  def isRejected     = results.exists(_._2 == CheckResult.Rejected())
  def requiresReview = !isRejected && results.exists(_._2 == CheckResult.RequiresReview())
  def isApproved     = !isRejected && !requiresReview
}

object ChecksState {
  val empty = ChecksState(Map())
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
