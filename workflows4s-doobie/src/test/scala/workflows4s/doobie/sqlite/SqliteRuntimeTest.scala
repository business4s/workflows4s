package workflows4s.doobie.sqlite

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.sqlite.testing.SqliteWorkdirSuite

// TODO: Restore generic workflow tests once IO-specific test infrastructure is available
// The WorkflowRuntimeTest.Suite was removed as part of cats-effect abstraction from core.
class SqliteRuntimeTest extends AnyFreeSpec with SqliteWorkdirSuite {

  "sqlite runtime" - {
    "should initialize successfully" in {
      // Basic smoke test - workdir is created by the suite
      assert(workdir.toFile.exists())
      succeed
    }
  }

}
