package workflow4s.example.withdrawal.checks

sealed trait ChecksEvent
object ChecksEvent {
  case class ChecksRun(results: Map[CheckKey, CheckResult])                          extends ChecksEvent
  case class CheckCompleted(results: Map[CheckKey, CheckResult], decision: Decision) extends ChecksEvent
  case class ReviewDecisionTaken(decision: ReviewDecision)                           extends ChecksEvent
}
