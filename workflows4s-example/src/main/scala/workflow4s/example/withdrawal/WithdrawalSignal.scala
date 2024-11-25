package workflow4s.example.withdrawal

import io.circe.Codec
import workflow4s.example.withdrawal.WithdrawalService.Iban

object WithdrawalSignal {

  case class CreateWithdrawal(amount: BigDecimal, recipient: Iban)

  sealed trait ExecutionCompleted derives Codec.AsObject
  object ExecutionCompleted {
    case object Succeeded extends ExecutionCompleted
    case object Failed    extends ExecutionCompleted
  }

  case class CancelWithdrawal(operatorId: String, comment: String, acceptStartedExecution: Boolean)
}
