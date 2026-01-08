package workflows4s.example.withdrawal.checks

import workflows4s.wio.LazyFutureWorkflowContext
import workflows4s.runtime.instanceengine.LazyFuture

/** LazyFuture-specific ChecksEngine for use with Pekko and other Future-based runtimes.
  */
object LazyFutureChecksEngineHelper {

  object Context extends LazyFutureWorkflowContext {
    override type Event = ChecksEvent
    override type State = ChecksState
  }

  def create(): ChecksEngine[LazyFuture, Context.Ctx] = {
    given workflows4s.runtime.instanceengine.Effect[LazyFuture] =
      workflows4s.runtime.instanceengine.LazyFuture.lazyFutureEffect(using Context.executionContext)
    new ChecksEngine[LazyFuture, Context.Ctx](Context)
  }
}
