package workflow4s.example.withdrawal.checks

import java.time.Instant

import io.circe.Codec
import workflow4s.example.pekko.PekkoCirceSerializer

sealed trait ChecksEvent derives Codec.AsObject
object ChecksEvent {
  case class ChecksRun(results: Map[CheckKey, CheckResult]) extends ChecksEvent
  case class ReviewDecisionTaken(decision: ReviewDecision)  extends ChecksEvent
  case class AwaitingRefresh(started: Instant)              extends ChecksEvent
  case class RefreshReleased(released: Instant)             extends ChecksEvent
  case class AwaitingTimeout(started: Instant)              extends ChecksEvent
  case class ExecutionTimedOut(releasedAt: Instant)         extends ChecksEvent

  class PekkoSerializer extends PekkoCirceSerializer[ChecksEvent] {
    override def identifier = 12345677
  }
}
