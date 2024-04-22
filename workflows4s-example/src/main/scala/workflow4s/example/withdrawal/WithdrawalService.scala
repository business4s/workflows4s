package workflow4s.example.withdrawal

import cats.effect.IO
import workflow4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban, NotEnoughFunds}
import workflow4s.example.withdrawal.checks.Check

trait WithdrawalService {
  def calculateFees(amount: BigDecimal): IO[Fee]

  def putMoneyOnHold(amount: BigDecimal): IO[Either[NotEnoughFunds, Unit]]

  def initiateExecution(amount: BigDecimal, recepient: Iban): IO[ExecutionResponse]

  def releaseFunds(amount: BigDecimal): IO[Unit]

  def cancelFundsLock(): IO[Unit]

  def getChecks(): List[Check[WithdrawalData.Validated]]
}

object WithdrawalService {

  case class NotEnoughFunds()

  case class Fee(value: BigDecimal)

  case class Iban(value: String)

  sealed trait ExecutionResponse
  object ExecutionResponse {
    case class Accepted(externalId: String) extends ExecutionResponse
    case class Rejected(error: String)      extends ExecutionResponse
  }
}
