package workflows4s.example.testuitls

import cats.effect.Async
import com.typesafe.scalalogging.StrictLogging
import doobie.util.log.{ExecFailure, LogEvent, LogHandler, ProcessingFailure, Success}

trait DoobieTestLogHandler[F[_]: Async] extends LogHandler[F], StrictLogging {
  override def run(logEvent: LogEvent): F[Unit] = logEvent match {
    case Success(sql, params, label, exec, processing)                    =>
      Async[F].delay(logger.debug(s"performing query: $sql"))
    case ProcessingFailure(sql, params, label, exec, processing, failure) =>
      val error = failure.getStackTrace().mkString("\n")
      Async[F].delay(logger.error(s"process failure for $sql: $error"))
    case ExecFailure(sql, params, label, exec, failure)                   =>
      val error = failure.getStackTrace().mkString("\n")
      Async[F].delay(logger.error(s"exec failure for $sql: $error"))
  }
}

object DoobieTestLogHandler {
  def make[F[_]: Async]: DoobieTestLogHandler[F] = new DoobieTestLogHandler[F] {}
}
