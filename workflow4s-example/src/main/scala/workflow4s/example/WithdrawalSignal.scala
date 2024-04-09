package workflow4s.example

import workflow4s.example.WithdrawalService.Iban

object WithdrawalSignal {

  case class CreateWithdrawal(amount: BigDecimal, recipient: Iban)

  sealed trait ExecutionCompleted
  object ExecutionCompleted {
    case object Succeeded extends ExecutionCompleted
    case object Failed    extends ExecutionCompleted
  }

  case class CancelWithdrawal(operatorId: String, comment: String)
}
