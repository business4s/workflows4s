package workflows4s.doobie.postgres.testing

import cats.effect.IO
import cats.implicits.toTraverseOps
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.scalatest.{BeforeAndAfterAll, Suite}

trait PostgresSuite extends TestContainerForAll with BeforeAndAfterAll { self: Suite =>

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def()

  override def beforeAll(): Unit = {
    if isDockerAvailable() then {
      super.beforeAll()
    } else {
      cancel("Docker is not available")
    }
  }

  private def isDockerAvailable(): Boolean = {
    try {
      import scala.sys.process.*
      import scala.concurrent.{Await, Future}
      import scala.concurrent.duration.*
      import scala.concurrent.ExecutionContext.Implicits.global
      val future = Future {
        Process("docker info").!(ProcessLogger(_ => ())) == 0
      }
      Await.result(future, 5.seconds)
    } catch {
      case _: Exception => false
    }
  }

  var xa: Transactor[IO] = scala.compiletime.uninitialized

  override def afterContainersStart(container: Containers): Unit = {
    super.afterContainersStart(container)
    xa = Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = container.jdbcUrl,
      user = container.username,
      password = container.password,
      logHandler = None,
    )
    import cats.effect.unsafe.implicits.global
    createSchema(xa).unsafeRunSync()
  }

  def createSchema(xa: Transactor[IO]): IO[Unit] = {
    val schemaSql         = scala.io.Source.fromResource("schema/postgres-schema.sql").mkString
    val registrySchemaSql = scala.io.Source.fromResource("schema/postgres-workflow-registry-schema.sql").mkString
    val statements        = (schemaSql + registrySchemaSql).split(";").map(_.trim).filter(_.nonEmpty)
    val actions           = statements.toList.traverse(sql => Fragment.const(sql).update.run)
    actions.transact(xa).map(_ => ())
  }

}
