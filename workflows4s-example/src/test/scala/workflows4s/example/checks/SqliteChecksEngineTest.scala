package workflows4s.example.checks

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.sqlite.testing.SqliteWorkdirSuite

// TODO: Re-enable these tests after implementing effect-polymorphic workflows or IO-based doobie adapters.
// Currently disabled because:
// - ChecksEngine uses IOWorkflowContext (Eff = IO)
// - SqliteRuntimeAdapter expects Result effect workflows
// - These effect types are incompatible without casting
// The workflow logic is tested via in-memory runtime (ChecksEngineTest)
// and generic doobie functionality is tested via SqliteRuntimeTest.
class SqliteChecksEngineTest extends AnyFreeSpec with SqliteWorkdirSuite {

  "sqlite" - {
    "checks engine integration test disabled (effect type mismatch)" in {
      // See class comment for explanation
      pending
    }
  }
}
