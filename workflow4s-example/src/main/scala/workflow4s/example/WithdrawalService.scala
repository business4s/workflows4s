package workflow4s.example

import cats.effect.IO
import workflow4s.example.WithdrawalService.{ExecutionResponse, Fee, Iban, NotEnoughFunds}

trait WithdrawalService {
  def calculateFees(amount: BigDecimal): IO[Fee]

  def putMoneyOnHold(amount: BigDecimal): IO[Either[NotEnoughFunds, Unit]]

  def initiateExecution(amount: BigDecimal, recepient: Iban): IO[ExecutionResponse]

  def releaseFunds(amount: BigDecimal): IO[Unit]

  def cancelFundsLock(): IO[Unit]
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
