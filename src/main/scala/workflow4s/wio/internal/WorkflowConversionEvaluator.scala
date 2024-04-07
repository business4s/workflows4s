package workflow4s.wio.internal

import workflow4s.wio.{WCEvent, WCState, WorkflowContext}

object WorkflowConversionEvaluator {

  trait WorkflowEmbedding[Inner <: WorkflowContext, Outer <: WorkflowContext, -Input] {
    def convertEvent(e: WCEvent[Inner]): WCEvent[Outer]
    def unconvertEvent(e: WCEvent[Outer]): Option[WCEvent[Inner]]

    type OutputState[In <: WCState[Inner]] <: WCState[Outer]
    def convertState[In <: WCState[Inner]](innerState: In, input: Input): OutputState[In]
  }

  object WorkflowEmbedding {
    type Aux[Inner <: WorkflowContext, Outer <: WorkflowContext, OS[_ <: WCState[Inner]] <: WCState[Outer], -Input] =
      WorkflowEmbedding[Inner, Outer, Input] { type OutputState[In <: WCState[Inner]] = OS[In] }
  }

}
