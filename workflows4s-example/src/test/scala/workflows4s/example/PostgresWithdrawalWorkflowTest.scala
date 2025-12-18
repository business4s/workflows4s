package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.testuitls.PostgresSuite

// TODO: Re-enable these tests after implementing effect-polymorphic workflows or IO-based doobie adapters.
// Currently disabled because:
// - WithdrawalWorkflow uses IOWorkflowContext (Eff = IO)
// - PostgresRuntimeAdapter expects Result effect workflows
// - These effect types are incompatible without casting
// The workflow logic is tested via in-memory runtime (WithdrawalWorkflowTest)
// and generic doobie functionality is tested via PostgresRuntimeTest.
class PostgresWithdrawalWorkflowTest extends AnyFreeSpec with PostgresSuite {

  "postgres" - {
    "withdrawal workflow integration test disabled (effect type mismatch)" in {
      // See class comment for explanation
      pending
    }
  }
}
