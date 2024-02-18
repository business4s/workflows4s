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
  sealed trait Final                                              extends CheckResult
  case class Approved()                                           extends Final
  case class Rejected()                                           extends Final
  case class Pending()                                            extends CheckResult
  case class RequiresReview()                                     extends CheckResult
  case class RequiresSignal[Req](signalDef: SignalDef[Req, Unit]) extends CheckResult // TODO will be hard to serialize
}

case class CheckKey(value: String)
trait Check[Data] {
  val key: CheckKey
  def run(data: Data): IO[CheckResult]
}

case class ChecksState(results: Map[CheckKey, CheckResult]) {
  def finished: Set[CheckKey] = ???

  def addResults(newResults: Map[CheckKey, CheckResult]) = ChecksState(results ++ newResults)
}

trait ChecksInput {
  type Data
  val data: Data
  val checks: Map[CheckKey, Check[Data]]
}