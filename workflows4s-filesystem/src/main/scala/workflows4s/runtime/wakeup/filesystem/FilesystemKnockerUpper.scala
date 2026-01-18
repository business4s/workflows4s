package workflows4s.runtime.wakeup.filesystem

import java.nio.file.Path
import java.time.{Clock, Instant}
import cats.effect.{IO, ResourceIO}
import cats.implicits.toFunctorOps
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.filesystem.FilesystemKnockerUpper.WorkflowInstanceIdConverter
import workflows4s.runtime.wakeup.filesystem.FsScheduler.TaskId

import java.util.Base64
import scala.util.Try

class FilesystemKnockerUpper(scheduler: FsScheduler)
    extends KnockerUpper.Process[IO, ResourceIO[Unit]]
    with KnockerUpper.Agent[IO]
    with StrictLogging {

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit] = {
    val taskId = TaskId(WorkflowInstanceIdConverter.toString(id))
    at match {
      case Some(value) => scheduler.schedule(taskId, value)
      case None        => scheduler.clearAll(taskId)
    }
  }

  override def initialize(wakeUp: WorkflowInstanceId => IO[Unit]): ResourceIO[Unit] = scheduler.events
    .evalMap(event => {
      (for {
        workflowId <- IO.fromEither(WorkflowInstanceIdConverter.fromString(event.entity))
        _          <- wakeUp(workflowId)
        _          <- IO(logger.info(s"Woken up for task ${event.entity} scheduled for ${event.scheduleTime}"))
        _          <- scheduler.clear(event.entity, event.scheduleTime)
      } yield ()).handleError(ex => logger.error(s"Failed to wakeup ${event.entity}", ex))
    })
    .compile
    .drain
    .background
    .void // we ditch the ability to wait for the stream to finish.
  // If it's necessary, we could expose it, but it will complicate the return type.
}

object FilesystemKnockerUpper extends StrictLogging {

  def create(workDir: Path): FilesystemKnockerUpper = {
    val scheduler = new PollingFsScheduler(fs2.io.file.Path.fromNioPath(workDir), Clock.systemUTC())
    new FilesystemKnockerUpper(scheduler)
  }

  object WorkflowInstanceIdConverter {

    private def encodeBase64(s: String): String = Base64.getUrlEncoder.withoutPadding.encodeToString(s.getBytes("UTF-8"))
    private def decodeBase64(s: String): String = new String(Base64.getUrlDecoder.decode(s), "UTF-8")
    private val separator                       = "::"

    def toString(wid: WorkflowInstanceId): String = s"${encodeBase64(wid.templateId)}${separator}${encodeBase64(wid.instanceId)}"

    def fromString(s: String): Either[Throwable, WorkflowInstanceId] = {
      s.split(separator, 2) match {
        case Array(encodedRuntime, encodedInstance) =>
          Try(WorkflowInstanceId(decodeBase64(encodedRuntime), decodeBase64(encodedInstance))).toEither
        case _                                      => Left(new RuntimeException(s"Invalid format: $s"))
      }
    }
  }

}
