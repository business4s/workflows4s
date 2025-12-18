package workflows4s.example.checks

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.testuitls.PostgresSuite

// TODO: Re-enable these tests after implementing effect-polymorphic workflows or IO-based doobie adapters.
// Currently disabled because:
// - ChecksEngine uses IOWorkflowContext (Eff = IO)
// - PostgresRuntimeAdapter expects Result effect workflows
// - These effect types are incompatible without casting
// The workflow logic is tested via in-memory runtime (ChecksEngineTest)
// and generic doobie functionality is tested via PostgresRuntimeTest.
class PostgresChecksEngineTest extends AnyFreeSpec with PostgresSuite {

  "postgres" - {
    "checks engine integration test disabled (effect type mismatch)" in {
      // See class comment for explanation
      pending
    }
  }
}
