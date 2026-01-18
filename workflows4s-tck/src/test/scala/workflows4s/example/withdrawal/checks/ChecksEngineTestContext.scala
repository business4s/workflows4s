package workflows4s.example.withdrawal.checks

import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.WorkflowContext

class ChecksEngineTestContext[F[_]](using E: Effect[F]) {

  object Context extends WorkflowContext {
    override type Event  = ChecksEvent
    override type State  = ChecksState
    override type Eff[A] = F[A]
    override given effect: Effect[Eff] = E
  }

  def createEngine(): ChecksEngine[F, Context.Ctx] =
    new ChecksEngine[F, Context.Ctx](Context)
}
