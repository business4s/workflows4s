package workflow4s.example

import workflow4s.example.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflow4s.wio.JournalWrite

sealed trait WithdrawalEvent
object WithdrawalEvent {
  case class WithdrawalInitiated(amount: BigDecimal, recipient: Iban)
  implicit val WithdrawalInitiatedJournalWrite: JournalWrite[WithdrawalInitiated] = null

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
}
