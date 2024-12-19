package workflows4s.runtime.wakeup.filesystem

import cats.effect.IO
import workflows4s.runtime.wakeup.filesystem.FsScheduler.TaskId

import java.time.Instant

trait FsScheduler {

  def schedule(id: TaskId, time: Instant, content: String = ""): IO[Unit]
  def clear(id: TaskId, time: Instant): IO[Unit]
  def clearAll(id: TaskId): IO[Unit]

  def events: fs2.Stream[IO, FsScheduler.Event]
}

object FsScheduler {

  opaque type TaskId <: String = String
  object TaskId {
    def apply(value: String): TaskId = value
  }
  case class Event(entity: TaskId, scheduleTime: Instant, content: String)

}
