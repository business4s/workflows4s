package workflow4s.example

import workflow4s.example.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflow4s.wio.JournalWrite

sealed trait WithdrawalEvent
object WithdrawalEvent {
  sealed trait ValidationResult                                      extends Product with Serializable
  case class WithdrawalAccepted(amount: BigDecimal, recipient: Iban) extends ValidationResult
  case class WithdrawalRejected(error: String)                       extends ValidationResult
  implicit val ValidationResultJW: JournalWrite[ValidationResult] = null

  case class FeeSet(fee: Fee)
  implicit val FeeSetJournalWrite: JournalWrite[FeeSet] = null

  sealed trait MoneyLocked extends WithdrawalEvent with Product with Serializable
  object MoneyLocked {
    case class Success()        extends MoneyLocked
    case class NotEnoughFunds() extends MoneyLocked
  }
  implicit val MoneyLockedWrite: JournalWrite[MoneyLocked] = null

  case class ExecutionInitiated(response: ExecutionResponse)
  implicit val executionInitiatedWrite: JournalWrite[ExecutionInitiated] = null

  case class ExecutionCompleted(status: WithdrawalSignal.ExecutionCompleted)
  implicit val ExecutionCompletedWrite: JournalWrite[ExecutionCompleted] = null

  case class MoneyReleased()
  implicit val MoneyReleasedWrite: JournalWrite[MoneyReleased] = null

  case class MoneyLockCancelled()
  implicit val MoneyLockCancelledJW: JournalWrite[MoneyLockCancelled] = null
}
