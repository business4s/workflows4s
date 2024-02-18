package workflow4s.example

sealed trait WithdrawalRejection

object WithdrawalRejection {

  sealed trait FromChecks extends WithdrawalRejection

  case class NotEnoughFunds()            extends WithdrawalRejection
  case class RejectedBySystem()          extends FromChecks
  case class RejectedByOperator()        extends FromChecks
  case class RejectedByExecutionEngine() extends FromChecks

}
