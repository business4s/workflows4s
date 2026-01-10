package workflows4s.example.withdrawal

import io.circe.Codec
import sttp.tapir.Schema
import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban, NotEnoughFunds}
import workflows4s.example.withdrawal.checks.Check

trait WithdrawalService[F[_]] {
  def calculateFees(amount: BigDecimal): F[Fee]

  def putMoneyOnHold(amount: BigDecimal): F[Either[NotEnoughFunds, Unit]]

  def initiateExecution(amount: BigDecimal, recepient: Iban): F[ExecutionResponse]

  def releaseFunds(amount: BigDecimal): F[Unit]

  def cancelFundsLock(): F[Unit]

  def getChecks(): List[Check[F, WithdrawalData.Validated]]
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
