package workflow4s.example

sealed trait WithdrawalData

object WithdrawalData {
  case object Empty                        extends WithdrawalData
  case class Initiated(amount: BigDecimal) extends WithdrawalData
}
