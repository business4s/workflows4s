package workflow4s.example.checks

import workflow4s.wio.JournalWrite

sealed trait ChecksEvent
object ChecksEvent {
  case class ChecksRun(results: Map[CheckKey, CheckResult])
  implicit val ChecksRunJW: JournalWrite[ChecksRun] = null

  case class CheckCompleted(results: Map[CheckKey, CheckResult], decision: Decision)
  implicit val CheckCompletedJW: JournalWrite[CheckCompleted] = null

  case class ReviewDecisionTaken(decision: ReviewDecision)
  implicit val ReviewDecisionTakenJW: JournalWrite[ReviewDecisionTaken] = null
}
