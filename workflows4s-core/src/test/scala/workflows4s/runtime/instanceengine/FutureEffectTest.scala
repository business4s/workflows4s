package workflows4s.runtime.instanceengine

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class FutureEffectTest extends AnyFreeSpec with EffectTestSuite[LazyFuture] with Matchers {

  given ExecutionContext = ExecutionContext.global

  import LazyFuture.lazyFutureEffect
  given effect: Effect[LazyFuture] = Effect[LazyFuture]

  "LazyFuture Effect" - {
    effectTests()
  }
}
