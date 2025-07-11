package workflows4s.example.docs.doobie

import workflows4s.utils.StringUtils
import doobie.util.meta.Meta

opaque type WorkflowId <: String = String
object WorkflowId {

  given Meta[WorkflowId] = Meta.StringMeta.imap(WorkflowId.apply)(_.toString)

  def generate(): WorkflowId           = StringUtils.randomAlphanumericString(12)
  def apply(value: String): WorkflowId = value
}
