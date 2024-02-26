package workflow4s.example

sealed trait WithdrawalRejection

object WithdrawalRejection {

  case class NotEnoughFunds()            extends WithdrawalRejection
  case class RejectedInChecks()          extends WithdrawalRejection
  case class RejectedByExecutionEngine() extends WithdrawalRejection

}
