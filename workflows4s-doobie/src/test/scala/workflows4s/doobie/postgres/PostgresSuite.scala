package workflows4s.doobie.postgres

import cats.effect.IO
import cats.implicits.toTraverseOps
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.scalatest.Suite

trait PostgresSuite extends TestContainerForAll { self: Suite =>

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def()

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
    val schemaSql  = scala.io.Source.fromResource("schema/postgres-schema.sql").mkString
    // Split the script into individual statements (if necessary) and execute them
    val statements = schemaSql.split(";").map(_.trim).filter(_.nonEmpty)
    val actions    = statements.toList.traverse(sql => Fragment.const(sql).update.run)
    actions.transact(xa).map(_ => ())
  }

}
