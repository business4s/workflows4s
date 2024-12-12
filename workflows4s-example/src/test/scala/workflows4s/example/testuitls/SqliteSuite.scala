package workflows4s.example.testuitls

import cats.effect.IO
import cats.implicits.toTraverseOps
import com.typesafe.scalalogging.StrictLogging
import doobie.*
import doobie.implicits.*
import doobie.util.log.{ExecFailure, LogEvent, ProcessingFailure, Success}
import doobie.util.transactor.Transactor
import org.scalatest.Suite

trait SqliteSuite { self: Suite & StrictLogging =>
  val logHandler         = new LogHandler[IO] {
    override def run(logEvent: LogEvent): IO[Unit] = logEvent match {
      case Success(sql, params, label, exec, processing) => IO.delay(logger.debug(s"performing query: $sql"))

      case ProcessingFailure(sql, params, label, exec, processing, failure) =>
        val error = failure.getStackTrace().mkString("\n")
        IO.delay(logger.error(s"process failure for $sql: $error"))

      case ExecFailure(sql, params, label, exec, failure) =>
        val error = failure.getStackTrace().mkString("\n")
        IO.delay(logger.error(s"exec failure for $sql: $error"))
    }
  }
  val xa: Transactor[IO] = Transactor.fromDriverManager[IO](driver = "org.sqlite.JDBC", url = "jdbc:sqlite:test.db", logHandler = Some(logHandler))

  def createSchema(xa: Transactor[IO]): IO[Unit] = {
    val schemaSql  = scala.io.Source.fromResource("schema/sqlite-schema.sql").mkString
    val statements = schemaSql.split(";").map(_.trim).filter(_.nonEmpty)
    val actions    = statements.toList.traverse(sql => Fragment.const(sql).update.run)
    actions.transact(xa).map(_ => ())
  }
}
