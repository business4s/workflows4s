package workflows4s.example.testuitls

import cats.effect.IO
import cats.implicits.{catsSyntaxOptionId, toTraverseOps}
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.scalatest.Suite

trait SqliteSuite { self: Suite =>
  val xa: Transactor[IO] =
    Transactor.fromDriverManager[IO](driver = "org.sqlite.JDBC", url = "jdbc:sqlite:test.db", logHandler = DoobieTestLogHandler.make[IO].some)

  def createSchema(xa: Transactor[IO]): IO[Unit] = {
    val schemaSql  = scala.io.Source.fromResource("schema/sqlite-schema.sql").mkString
    val statements = schemaSql.split(";").map(_.trim).filter(_.nonEmpty)
    val actions    = statements.toList.traverse(sql => Fragment.const(sql).update.run)
    actions.transact(xa).map(_ => ())
  }
}
