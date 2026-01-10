package workflows4s.example.withdrawal

import workflows4s.wio.LazyFutureWorkflowContext
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.runtime.instanceengine.LazyFuture

/** LazyFuture-specific WithdrawalWorkflow helper for use with Pekko and other Future-based runtimes.
  */
object LazyFutureWithdrawalWorkflowHelper {

  object Context extends LazyFutureWorkflowContext {
    override type Event = WithdrawalEvent
    override type State = WithdrawalData
  }

  object ChecksEngineContext extends LazyFutureWorkflowContext {
    override type Event = workflows4s.example.withdrawal.checks.ChecksEvent
    override type State = workflows4s.example.withdrawal.checks.ChecksState
  }

  def create(
      service: WithdrawalService[LazyFuture],
      checksEngine: ChecksEngine[LazyFuture, ChecksEngineContext.Ctx],
  ): WithdrawalWorkflow[LazyFuture, Context.Ctx, ChecksEngineContext.Ctx] = {
    given workflows4s.runtime.instanceengine.Effect[LazyFuture] =
      workflows4s.runtime.instanceengine.LazyFuture.lazyFutureEffect(using Context.executionContext)
    new WithdrawalWorkflow[LazyFuture, Context.Ctx, ChecksEngineContext.Ctx](Context, service, checksEngine)
  }
}
