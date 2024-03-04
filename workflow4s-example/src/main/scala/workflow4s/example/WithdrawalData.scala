package workflow4s.example

import workflow4s.example.WithdrawalService.Fee
import workflow4s.example.checks.ChecksState

sealed trait WithdrawalData

object WithdrawalData {
  case class Empty(txId: String)                                                           extends WithdrawalData {
    def initiated(amount: BigDecimal) = Initiated(txId, amount)
  }
  case class Initiated(txId: String, amount: BigDecimal)                                    extends WithdrawalData {
    def validated(fee: Fee) = Validated(txId, amount, fee)
  }
  case class Validated(txId: String, amount: BigDecimal, fee: Fee)                          extends WithdrawalData {
    def checked(checksState: ChecksState) = Checked(txId, amount, fee, checksState)
  }
  case class Checked(txId: String, amount: BigDecimal, fee: Fee, checkResults: ChecksState) extends WithdrawalData

  case class Executed(txId: String, amount: BigDecimal, fee: Fee, checkResults: ChecksState, externalTransactionId: String) extends WithdrawalData

  case class Completed() extends WithdrawalData
}
