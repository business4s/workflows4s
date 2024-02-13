package workflow4s.example

import workflow4s.example.WithdrawalService.Fee
import workflow4s.wio.JournalWrite

sealed trait WithdrawalEvent
object WithdrawalEvent {
  case class WithdrawalInitiated(amount: BigDecimal)
  implicit val WithdrawalInitiatedJournalWrite: JournalWrite[WithdrawalInitiated] = null

  case class FeeSet(fee: Fee)
  implicit val FeeSetJournalWrite: JournalWrite[FeeSet] = null
}