package workflows4s.doobie.sqlite

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.postgres.testing.JavaSerdeEventCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.testing.WorkflowRuntimeTest
import workflows4s.wio.TestCtx2

class SqliteRuntimeTest extends AnyFreeSpec with SqliteWorkdirSuite with WorkflowRuntimeTest.Suite {

  "generic tests" - {
    workflowTests(new SqliteRuntimeAdapter[TestCtx2.Ctx](workdir, JavaSerdeEventCodec.get))
  }

}
