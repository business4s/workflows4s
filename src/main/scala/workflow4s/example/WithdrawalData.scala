package workflow4s.example

import workflow4s.example.WithdrawalService.Fee

sealed trait WithdrawalData

object WithdrawalData {
  case object Empty                        extends WithdrawalData
  case class Initiated(amount: BigDecimal, fee: Option[Fee]) extends WithdrawalData
}
