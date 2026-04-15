package workflows4s.runtime.wakeup.filesystem

import java.time.Instant

import workflows4s.runtime.wakeup.filesystem.FsScheduler.TaskId

/** File-based task scheduler. Tasks are represented as files in a directory, with the scheduled time encoded in the filename. The `events` stream
  * emits tasks as they become due.
  */
trait FsScheduler[F[_]] {

  def schedule(id: TaskId, time: Instant, content: String = ""): F[Unit]
  def clear(id: TaskId, time: Instant): F[Unit]
  def clearAll(id: TaskId): F[Unit]

  /** Stream that polls the filesystem and emits events for tasks whose scheduled time has passed. */
  def events: fs2.Stream[F, FsScheduler.Event]
}

object FsScheduler {

  opaque type TaskId <: String = String
  object TaskId {
    def apply(value: String): TaskId = value
  }
  case class Event(entity: TaskId, scheduleTime: Instant, content: String)

}
