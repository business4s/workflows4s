package workflows4s.example.docs

import workflows4s.cats.IOWorkflowContext

object AppendOnlyPersistance {

  // start_example
  object Context extends IOWorkflowContext {
    override type State = MyState
    override type Event = MyState
  }
  // end_example

}
