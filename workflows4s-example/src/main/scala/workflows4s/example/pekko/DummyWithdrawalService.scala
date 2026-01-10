package workflows4s.example.pekko

import java.util.UUID

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflows4s.example.withdrawal.checks.Check
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalService}

object DummyWithdrawalService extends WithdrawalService[IO] {

  override def calculateFees(amount: BigDecimal): IO[WithdrawalService.Fee] = IO(WithdrawalService.Fee(amount * 0.1))

  override def putMoneyOnHold(amount: BigDecimal): IO[Either[WithdrawalService.NotEnoughFunds, Unit]] =
    IO(
      if amount > 1000 then WithdrawalService.NotEnoughFunds().asLeft
      else ().asRight,
    )

  override def initiateExecution(amount: BigDecimal, recepient: WithdrawalService.Iban): IO[WithdrawalService.ExecutionResponse] = {
    val hasUnwantedDecimals = amount.setScale(2) != amount
    IO(
      if hasUnwantedDecimals then WithdrawalService.ExecutionResponse.Rejected("Invalid precision! Only 2 decimal places expected.")
      else WithdrawalService.ExecutionResponse.Accepted(UUID.randomUUID().toString),
    )
  }

  override def releaseFunds(amount: BigDecimal): IO[Unit] = IO.unit

  override def cancelFundsLock(): IO[Unit] = IO.unit

  override def getChecks(): List[Check[IO, WithdrawalData.Validated]] = List()
}
