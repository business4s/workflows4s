package workflow4s.example

sealed trait WithdrawalRejection

object WithdrawalRejection {

  case class NotEnoughFunds()            extends WithdrawalRejection
  case class RejectedInChecks(txId: String)          extends WithdrawalRejection
  case class RejectedByExecutionEngine(txId: String) extends WithdrawalRejection

}
