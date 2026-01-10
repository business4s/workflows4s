package workflows4s.example.withdrawal

import workflows4s.example.withdrawal.checks.{ChecksEvent, ChecksState}
import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.WorkflowContext

class WithdrawalWorkflowTestContext[F[_]](using E: Effect[F]) {

  object Context extends WorkflowContext {
    override type Event  = WithdrawalEvent
    override type State  = WithdrawalData
    override type Eff[A] = F[A]
    override given effect: Effect[Eff] = E
  }

  object ChecksContext extends WorkflowContext {
    override type Event  = ChecksEvent
    override type State  = ChecksState
    override type Eff[A] = F[A]
    override given effect: Effect[Eff] = E
  }

  def createWorkflow(
      service: WithdrawalService[F],
      checksEngine: checks.ChecksEngine[F, ChecksContext.Ctx],
  ): WithdrawalWorkflow[F, Context.Ctx, ChecksContext.Ctx] = {
    new WithdrawalWorkflow[F, Context.Ctx, ChecksContext.Ctx](Context, service, checksEngine)
  }
}
