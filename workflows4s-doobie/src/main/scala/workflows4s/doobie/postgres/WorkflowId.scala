package workflows4s.doobie.postgres

import doobie.Meta

// Postgres locks are identified with a single bigint.
// We reuse the same type for id and use it directly for lock id to limit the runtime complexity
case class WorkflowId(value: Long) extends AnyVal

object WorkflowId {
  given Meta[WorkflowId] = Meta.LongMeta.imap(WorkflowId.apply)(_.value)
}
