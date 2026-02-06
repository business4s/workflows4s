package workflows4s.runtime.wakeup.filesystem

import java.time.Instant

import cats.effect.IO
import workflows4s.runtime.wakeup.filesystem.FsScheduler.TaskId

/** File-based task scheduler. Tasks are represented as files in a directory, with the scheduled time
  * encoded in the filename. The `events` stream emits tasks as they become due.
  */
trait FsScheduler {

  def schedule(id: TaskId, time: Instant, content: String = ""): IO[Unit]
  def clear(id: TaskId, time: Instant): IO[Unit]
  def clearAll(id: TaskId): IO[Unit]

  /** Stream that polls the filesystem and emits events for tasks whose scheduled time has passed. */
  def events: fs2.Stream[IO, FsScheduler.Event]
}

object FsScheduler {

  opaque type TaskId <: String = String
  object TaskId {
    def apply(value: String): TaskId = value
  }
  case class Event(entity: TaskId, scheduleTime: Instant, content: String)

}
