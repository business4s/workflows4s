package workflows4s.example.withdrawal

import cats.effect.IO
import workflows4s.cats.IOWorkflowContext
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.cats.CatsEffect.given

/** IO-specific WithdrawalWorkflow helper for use in the example application.
  */
object IOWithdrawalWorkflow {

  object Context extends IOWorkflowContext {
    override type Event = WithdrawalEvent
    override type State = WithdrawalData
  }

  object ChecksEngineContext extends IOWorkflowContext {
    override type Event = workflows4s.example.withdrawal.checks.ChecksEvent
    override type State = workflows4s.example.withdrawal.checks.ChecksState
  }

  def create(
      service: WithdrawalService[IO],
      checksEngine: ChecksEngine[IO, ChecksEngineContext.Ctx],
  ): WithdrawalWorkflow[IO, Context.Ctx, ChecksEngineContext.Ctx] = {
    new WithdrawalWorkflow[IO, Context.Ctx, ChecksEngineContext.Ctx](Context, service, checksEngine)
  }
}
