package workflows4s.doobie.sqlite

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.postgres.testing.JavaSerdeEventCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.doobie.testing.{ResultTestCtx2, ResultWorkflowRuntimeTest}

class SqliteRuntimeTest extends AnyFreeSpec with SqliteWorkdirSuite with ResultWorkflowRuntimeTest.Suite {

  "generic tests" - {
    resultWorkflowTests(new SqliteRuntimeAdapter[ResultTestCtx2.Ctx](workdir, JavaSerdeEventCodec.get))
  }

}
