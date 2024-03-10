package workflow4s.example

sealed trait WithdrawalRejection extends Product with Serializable

object WithdrawalRejection {

  case class InvalidInput(error: String)                            extends WithdrawalRejection
  case class NotEnoughFunds()                                       extends WithdrawalRejection
  case class RejectedInChecks(txId: String)                         extends WithdrawalRejection
  case class RejectedByExecutionEngine(txId: String, error: String) extends WithdrawalRejection

}
