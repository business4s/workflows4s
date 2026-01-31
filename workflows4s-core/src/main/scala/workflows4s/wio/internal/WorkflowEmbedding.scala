package workflows4s.wio.internal

import workflows4s.wio.{WCEvent, WCState, WorkflowContext}

trait WorkflowEmbedding[Inner <: WorkflowContext, Outer <: WorkflowContext, -Input] { self =>

  type InnerEvent = WCEvent[Inner]
  type OuterEvent = WCEvent[Outer]
  type InnerState = WCState[Inner]
  type OuterState = WCState[Outer]

  def convertEvent(e: InnerEvent): OuterEvent
  def unconvertEvent(e: OuterEvent): Option[InnerEvent]

  type OutputState[In <: InnerState] <: OuterState
  def convertState[In <: InnerState](innerState: In, input: Input): OutputState[In]

  // This is asymmetric because currently there is no way to ensure the upper bound of state within a workflow
  def unconvertState(outerState: OuterState): Option[InnerState]

  def unconvertStateUnsafe(outerState: OuterState): InnerState = unconvertState(outerState)
    .getOrElse(
      throw new Exception(
        "Cannot convert the state of the embedding workflow into the state of the embedded one. " +
          "This means that the outer workflow produced a state not handled in the embedding logic.\n" +
          s"Outer state: ${outerState}",
      ),
    )

  def contramap[NewInput](f: NewInput => Input): WorkflowEmbedding.Aux[Inner, Outer, OutputState, NewInput] =
    new WorkflowEmbedding[Inner, Outer, NewInput] {
      type InnerEvent = self.InnerEvent
      type OuterEvent = self.OuterEvent
      type InnerState = self.InnerState
      type OuterState = self.OuterState
      def convertEvent(e: InnerEvent): OuterEvent           = self.convertEvent(e)
      def unconvertEvent(e: OuterEvent): Option[InnerEvent] = self.unconvertEvent(e)
      type OutputState[In <: InnerState] = self.OutputState[In]
      def convertState[In <: InnerState](innerState: In, input: NewInput): OutputState[In] = self.convertState(innerState, f(input))
      def unconvertState(outerState: OuterState): Option[InnerState]                       = self.unconvertState(outerState)
    }
}

object WorkflowEmbedding {

  trait Event[From, To] {
    def convertEvent(e: From): To
    def unconvertEvent(e: To): Option[From]
  }

  type Aux[Inner <: WorkflowContext, Outer <: WorkflowContext, OS[_ <: WCState[Inner]] <: WCState[Outer], -Input] =
    WorkflowEmbedding[Inner, Outer, Input] {
      type InnerEvent                        = WCEvent[Inner]
      type OuterEvent                        = WCEvent[Outer]
      type InnerState                        = WCState[Inner]
      type OuterState                        = WCState[Outer]
      type OutputState[In <: WCState[Inner]] = OS[In]
    }

}
