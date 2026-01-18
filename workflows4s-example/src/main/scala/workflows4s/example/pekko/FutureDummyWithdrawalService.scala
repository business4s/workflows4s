package workflows4s.example.pekko

import java.util.UUID
import workflows4s.runtime.instanceengine.LazyFuture
import workflows4s.example.withdrawal.checks.Check
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalService}

class FutureDummyWithdrawalService extends WithdrawalService[LazyFuture] {

  override def calculateFees(amount: BigDecimal): LazyFuture[WithdrawalService.Fee] =
    LazyFuture.successful(WithdrawalService.Fee(amount * 0.1))

  override def putMoneyOnHold(amount: BigDecimal): LazyFuture[Either[WithdrawalService.NotEnoughFunds, Unit]] =
    LazyFuture.successful(
      if amount > 1000 then Left(WithdrawalService.NotEnoughFunds())
      else Right(()),
    )

  override def initiateExecution(amount: BigDecimal, recepient: WithdrawalService.Iban): LazyFuture[WithdrawalService.ExecutionResponse] = {
    val hasUnwantedDecimals = amount.setScale(2) != amount
    LazyFuture.successful(
      if hasUnwantedDecimals then WithdrawalService.ExecutionResponse.Rejected("Invalid precision! Only 2 decimal places expected.")
      else WithdrawalService.ExecutionResponse.Accepted(UUID.randomUUID().toString),
    )
  }

  override def releaseFunds(amount: BigDecimal): LazyFuture[Unit] = LazyFuture.successful(())

  override def cancelFundsLock(): LazyFuture[Unit] = LazyFuture.successful(())

  override def getChecks(): List[Check[LazyFuture, WithdrawalData.Validated]] = List()
}

object FutureDummyWithdrawalService {
  def apply(): FutureDummyWithdrawalService = new FutureDummyWithdrawalService
}
