package workflows4s.wio.internal

import workflows4s.wio.{WCEvent, WCState, WorkflowContext}

trait WorkflowEmbedding[Inner <: WorkflowContext, Outer <: WorkflowContext, -Input] {
  def convertEvent(e: WCEvent[Inner]): WCEvent[Outer]
  def unconvertEvent(e: WCEvent[Outer]): Option[WCEvent[Inner]]

  type OutputState[In <: WCState[Inner]] <: WCState[Outer]
  def convertState[In <: WCState[Inner]](innerState: In, input: Input): OutputState[In]
  // TODO can we help with assuring symetry on user side?
  def unconvertState(outerState: WCState[Outer]): Option[WCState[Inner]]
}

object WorkflowEmbedding {
  type Aux[Inner <: WorkflowContext, Outer <: WorkflowContext, OS[_ <: WCState[Inner]] <: WCState[Outer], -Input] =
    WorkflowEmbedding[Inner, Outer, Input] { type OutputState[In <: WCState[Inner]] = OS[In] }

  trait EventEmbedding[From, To] {
    def convertEvent(e: From): To
    def unconvertEvent(e: To): Option[From]
  }
}
