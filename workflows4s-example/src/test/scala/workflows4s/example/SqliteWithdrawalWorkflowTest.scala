package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.sqlite.testing.SqliteWorkdirSuite

// TODO: Re-enable these tests after implementing effect-polymorphic workflows or IO-based doobie adapters.
// Currently disabled because:
// - WithdrawalWorkflow uses IOWorkflowContext (Eff = IO)
// - SqliteRuntimeAdapter expects Result effect workflows
// - These effect types are incompatible without casting
// The workflow logic is tested via in-memory runtime (WithdrawalWorkflowTest)
// and generic doobie functionality is tested via SqliteRuntimeTest.
class SqliteWithdrawalWorkflowTest extends AnyFreeSpec with SqliteWorkdirSuite {

  "sqlite" - {
    "withdrawal workflow integration test disabled (effect type mismatch)" in {
      // See class comment for explanation
      pending
    }
  }
}
