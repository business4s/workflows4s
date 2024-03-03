package workflow4s.example

import cats.effect.IO
import workflow4s.example.WithdrawalService.{Fee, NotEnoughFunds}

trait WithdrawalService {
  def calculateFees(amount: BigDecimal): IO[Fee]

  def putMoneyOnHold(amount: BigDecimal): IO[Either[NotEnoughFunds, Unit]]
}

object WithdrawalService {

  case class NotEnoughFunds()

  case class Fee(value: BigDecimal)
}
