package workflows4s.example.withdrawal

import io.circe.Codec
import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflows4s.example.withdrawal.checks.ChecksEvent

sealed trait WithdrawalEvent derives Codec.AsObject

object WithdrawalEvent {
  sealed trait ValidationResult                                                    extends WithdrawalEvent with Product with Serializable
  case class WithdrawalAccepted(txId: String, amount: BigDecimal, recipient: Iban) extends ValidationResult
  case class WithdrawalRejected(error: String)                                     extends ValidationResult

  case class FeeSet(fee: Fee) extends WithdrawalEvent

  sealed trait MoneyLocked extends WithdrawalEvent with Product with Serializable
  object MoneyLocked {
    case class Success()        extends MoneyLocked
    case class NotEnoughFunds() extends MoneyLocked
  }

  case class ChecksRun(inner: ChecksEvent) extends WithdrawalEvent

  case class ExecutionInitiated(response: ExecutionResponse) extends WithdrawalEvent

  case class ExecutionCompleted(status: WithdrawalSignal.ExecutionCompleted) extends WithdrawalEvent

  case class MoneyReleased()                 extends WithdrawalEvent
  case class RejectionHandled(error: String) extends WithdrawalEvent

  case class WithdrawalCancelledByOperator(operatorId: String, comment: String) extends WithdrawalEvent
}
