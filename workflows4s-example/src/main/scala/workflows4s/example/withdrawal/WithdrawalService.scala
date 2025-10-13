package workflows4s.example.withdrawal

import cats.effect.IO
import io.circe.Codec
import sttp.tapir.Schema
import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban, NotEnoughFunds}
import workflows4s.example.withdrawal.checks.Check

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

  case class Fee(value: BigDecimal) derives Codec.AsObject

  case class Iban(value: String) derives Codec.AsObject, Schema

  sealed trait ExecutionResponse derives Codec.AsObject
  object ExecutionResponse {
    case class Accepted(externalId: String) extends ExecutionResponse
    case class Rejected(error: String)      extends ExecutionResponse
  }
}
