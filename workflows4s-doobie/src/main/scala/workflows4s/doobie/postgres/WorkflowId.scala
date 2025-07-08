package workflows4s.doobie.postgres

import workflows4s.utils.StringUtils
import doobie.util.meta.Meta

opaque type WorkflowId <: String = String

object WorkflowId {

  given Meta[WorkflowId] = Meta.StringMeta.imap(WorkflowId.apply)(_.value)

  def generate(): WorkflowId           = StringUtils.randomAlphanumericString(12)
  def apply(value: String): WorkflowId = value

  extension (wid: WorkflowId) {
    def value: String = wid

    // Postgres locks are identified with a single bigint.
    // We use a SHA-256 hash of the string to generate a unique bigint
    def lockKey(): Long = StringUtils.stringToLong(wid)
  }
}
