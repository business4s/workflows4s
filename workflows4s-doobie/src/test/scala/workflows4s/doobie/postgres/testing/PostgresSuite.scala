package workflows4s.doobie.postgres.testing

import cats.effect.{Async, IO}
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

  private var jdbcUrl: String      = scala.compiletime.uninitialized
  private var jdbcUser: String     = scala.compiletime.uninitialized
  private var jdbcPassword: String = scala.compiletime.uninitialized

  def transactorFor[F[_]: Async]: Transactor[F] =
    Transactor.fromDriverManager[F](
      driver = "org.postgresql.Driver",
      url = jdbcUrl,
      user = jdbcUser,
      password = jdbcPassword,
      logHandler = None,
    )

  override def afterContainersStart(container: Containers): Unit = {
    super.afterContainersStart(container)
    jdbcUrl = container.jdbcUrl
    jdbcUser = container.username
    jdbcPassword = container.password
    xa = transactorFor[IO]
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
