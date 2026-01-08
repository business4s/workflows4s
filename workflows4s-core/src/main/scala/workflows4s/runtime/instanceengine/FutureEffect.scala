package workflows4s.runtime.instanceengine

import scala.concurrent.ExecutionContext

/** Effect instance for Future-like operations, implemented using LazyFuture. This provides a "Future" API while using LazyFuture internally.
  */
object FutureEffect {

  def futureEffect(using ec: ExecutionContext): Effect[LazyFuture] =
    LazyFuture.lazyFutureEffect(using ec)
}
