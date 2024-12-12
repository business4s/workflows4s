package workflows4s.doobie.sqlite

import doobie.Meta

case class WorkflowId(value: Long) extends AnyVal

object WorkflowId {
  given Meta[WorkflowId] = Meta.LongMeta.imap(WorkflowId.apply)(_.value)
}
