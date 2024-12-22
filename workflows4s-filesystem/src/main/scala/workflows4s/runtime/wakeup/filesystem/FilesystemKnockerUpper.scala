package workflows4s.runtime.wakeup.filesystem

import cats.effect.{IO, ResourceIO}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.filesystem.FilesystemKnockerUpper.StringCodec
import workflows4s.runtime.wakeup.filesystem.FsScheduler.TaskId

import java.nio.file.Path
import java.time.{Clock, Instant}

class FilesystemKnockerUpper[Id](scheduler: FsScheduler)(using idCodec: StringCodec[Id])
    extends KnockerUpper.Process[IO, Id, ResourceIO[Unit]]
    with KnockerUpper.Agent[Id]
    with StrictLogging {

  override def updateWakeup(id: Id, at: Option[Instant]): IO[Unit] = {
    val taskId = TaskId(idCodec.encode(id))
    at match {
      case Some(value) => scheduler.schedule(taskId, value)
      case None        => scheduler.clearAll(taskId)
    }
  }

  override def initialize(wakeUp: Id => IO[Unit]): ResourceIO[Unit] = scheduler.events
    .evalTap(x => IO(println(x)))
    .evalMap(event => {
      (for {
        _ <- wakeUp(idCodec.decode(event.entity))
        _ <- IO(logger.info(s"Woken up for task ${event.entity} scheduled for ${event.scheduleTime}"))
        _ <- scheduler.clear(event.entity, event.scheduleTime)
      } yield ()).handleError(ex => logger.error(s"Failed to wakeup ${event.entity}",ex))
    })
    .compile
    .drain
    .background
    .map(_.void)
}

object FilesystemKnockerUpper extends StrictLogging {

  trait StringCodec[T] {
    def encode(value: T): String
    def decode(value: String): T
  }

  def create[Id: StringCodec](workDir: Path): FilesystemKnockerUpper[Id] = {
    val scheduler = new PollingFsScheduler(fs2.io.file.Path.fromNioPath(workDir), Clock.systemUTC())
    new FilesystemKnockerUpper[Id](scheduler)
  }

}
