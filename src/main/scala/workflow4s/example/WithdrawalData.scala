package workflow4s.example

import workflow4s.example.WithdrawalService.Fee
import workflow4s.example.checks.ChecksState

sealed trait WithdrawalData

object WithdrawalData {
  case object Empty                                                           extends WithdrawalData
  case class Initiated(amount: BigDecimal)                                    extends WithdrawalData {
    def validated(fee: Fee) = Validated(amount, fee)
  }
  case class Validated(amount: BigDecimal, fee: Fee)                          extends WithdrawalData {
    def checked(checksState: ChecksState) = Checked(amount, fee, checksState)
  }
  case class Checked(amount: BigDecimal, fee: Fee, checkResults: ChecksState) extends WithdrawalData

  case class Executed(amount: BigDecimal, fee: Fee, checkResults: ChecksState, externalTransactionId: String) extends WithdrawalData
}
