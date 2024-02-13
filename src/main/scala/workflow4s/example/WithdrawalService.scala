package workflow4s.example

import cats.effect.IO
import workflow4s.example.WithdrawalService.Fee

trait WithdrawalService {
  def calculateFees(amount: BigDecimal): IO[Fee]
}

object WithdrawalService {
  case class Fee(value: BigDecimal)
}
