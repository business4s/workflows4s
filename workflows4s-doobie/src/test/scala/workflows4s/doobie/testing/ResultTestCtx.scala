package workflows4s.doobie.testing

import workflows4s.doobie.ResultWorkflowContext
import workflows4s.wio.TestState

/** Result-based test context for doobie tests. Uses the internal Result effect type.
  */
object ResultTestCtx2 extends ResultWorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = TestState
}
