package workflows4s.example.withdrawal.checks

import cats.effect.IO
import workflows4s.cats.IOWorkflowContext
import workflows4s.cats.CatsEffect.given

/** IO-specific ChecksEngine for use in the example application.
  */
object IOChecksEngine {

  object Context extends IOWorkflowContext {
    override type Event = ChecksEvent
    override type State = ChecksState
  }

  def create(): ChecksEngine[IO, Context.Ctx] = {
    new ChecksEngine[IO, Context.Ctx](Context)
  }
}
