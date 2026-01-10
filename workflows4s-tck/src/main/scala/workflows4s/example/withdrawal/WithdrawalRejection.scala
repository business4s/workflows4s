package workflows4s.example.withdrawal

sealed trait WithdrawalRejection extends Product with Serializable

object WithdrawalRejection {

  case class InvalidInput(error: String)                    extends WithdrawalRejection
  case class NotEnoughFunds()                               extends WithdrawalRejection
  case class RejectedInChecks()                             extends WithdrawalRejection
  case class RejectedByExecutionEngine(error: String)       extends WithdrawalRejection
  case class Cancelled(operatorId: String, comment: String) extends WithdrawalRejection

}
