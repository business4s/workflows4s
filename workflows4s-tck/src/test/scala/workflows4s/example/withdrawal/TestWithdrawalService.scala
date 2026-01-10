package workflows4s.example.withdrawal

import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban, NotEnoughFunds}
import workflows4s.example.withdrawal.checks.Check
import workflows4s.runtime.instanceengine.Effect

/** Configurable stub implementation of WithdrawalService for testing. Configure behavior by setting the response fields before running tests.
  */
class TestWithdrawalService[F[_]](using E: Effect[F]) extends WithdrawalService[F] {

  // Configurable responses
  var feeResponse: Fee                                 = Fee(0)
  var holdResponse: Either[NotEnoughFunds, Unit]       = Right(())
  var executionResponse: () => ExecutionResponse       = () => ExecutionResponse.Accepted("ext-1")
  var checks: List[Check[F, WithdrawalData.Validated]] = List()

  // Track invocations
  var releaseFundsCalled: Boolean    = false
  var cancelFundsLockCalled: Boolean = false

  override def calculateFees(amount: BigDecimal): F[Fee] =
    E.pure(feeResponse)

  override def putMoneyOnHold(amount: BigDecimal): F[Either[NotEnoughFunds, Unit]] =
    E.pure(holdResponse)

  override def initiateExecution(amount: BigDecimal, recipient: Iban): F[ExecutionResponse] =
    E.delay(executionResponse())

  override def releaseFunds(amount: BigDecimal): F[Unit] = {
    releaseFundsCalled = true
    E.unit
  }

  override def cancelFundsLock(): F[Unit] = {
    cancelFundsLockCalled = true
    E.unit
  }

  override def getChecks(): List[Check[F, WithdrawalData.Validated]] =
    checks

  // Helper methods for test setup
  def withFees(fee: Fee): TestWithdrawalService[F] = {
    feeResponse = fee
    this
  }

  def withHoldSuccess(): TestWithdrawalService[F] = {
    holdResponse = Right(())
    this
  }

  def withHoldFailure(): TestWithdrawalService[F] = {
    holdResponse = Left(NotEnoughFunds())
    this
  }

  def withExecutionAccepted(externalId: String): TestWithdrawalService[F] = {
    executionResponse = () => ExecutionResponse.Accepted(externalId)
    this
  }

  def withExecutionRejected(error: String): TestWithdrawalService[F] = {
    executionResponse = () => ExecutionResponse.Rejected(error)
    this
  }

  def withExecutionResponse(response: () => ExecutionResponse): TestWithdrawalService[F] = {
    executionResponse = response
    this
  }

  def withChecks(checksList: List[Check[F, WithdrawalData.Validated]]): TestWithdrawalService[F] = {
    checks = checksList
    this
  }

  def reset(): Unit = {
    releaseFundsCalled = false
    cancelFundsLockCalled = false
  }
}

object TestWithdrawalService {
  def apply[F[_]](using E: Effect[F]): TestWithdrawalService[F] = new TestWithdrawalService[F]
}
