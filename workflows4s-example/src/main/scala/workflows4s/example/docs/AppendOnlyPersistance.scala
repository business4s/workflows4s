package workflows4s.example.docs

import workflows4s.wio.WorkflowContext

object AppendOnlyPersistance {

  // start_example
  object Context extends WorkflowContext {
    type Effect         = cats.effect.IO
    override type State = MyState
    override type Event = MyState
  }
  // end_example

}
