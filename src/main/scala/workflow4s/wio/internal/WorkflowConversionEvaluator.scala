package workflow4s.wio.internal

import workflow4s.wio.WorkflowContext

object WorkflowConversionEvaluator {

  trait WorkflowEmbedding[Inner <: WorkflowContext, Outer <: WorkflowContext, -Input] {
    def convertEvent(e: Inner#Event): Outer#Event
    def unconvertEvent(e: Outer#Event): Option[Inner#Event]

    type OutputState[In <: Inner#State] <: Outer#State
    def convertState[In <: Inner#State](innerState: In, input: Input): OutputState[In]
  }

  object WorkflowEmbedding {
    type Aux[Inner <: WorkflowContext, Outer <: WorkflowContext, OS[_] <: Outer#State, -Input] = WorkflowEmbedding[Inner, Outer, Input] {type OutputState[In] = OS[In]}
  }

}
