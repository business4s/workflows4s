package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.withdrawal.*
import workflows4s.runtime.instanceengine.{Effect, FutureEffect, LazyFuture}
import workflows4s.testing.WorkflowTestAdapter

import scala.concurrent.ExecutionContext.Implicits.global

class FutureWithdrawalWorkflowTest extends AnyFreeSpec with WithdrawalWorkflowTestSuite[LazyFuture] {

  override given effect: Effect[LazyFuture] = FutureEffect.futureEffect

  "in-memory" - {
    val adapter = new WorkflowTestAdapter.InMemory[LazyFuture, testContext.Context.Ctx]()
    withdrawalTests(adapter)
  }
}
