package workflows4s.wio

import workflows4s.wio.builders.{AllBuilders, InterruptionBuilder}
import workflows4s.runtime.instanceengine.Effect

/** WorkflowContext defines the type universe for a workflow including:
  *   - State: The workflow state type
  *   - Event: The workflow event type
  *   - Eff: The effect type (e.g., IO, Future, Id) - locked in at context creation
  */
trait WorkflowContext { ctx: WorkflowContext =>
  type Event
  type State

  /** The effect type for this workflow. Override to specify the effect system.
    */
  type Eff[_]

  /** Given Effect instance for Eff. Must be provided by implementing context.
    */
  given effect: Effect[Eff]

  type Ctx = WorkflowContext.AUX[State, Event, Eff]

  /** WIO type alias using the context's effect type */
  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[Eff, In, Err, Out, Ctx]

  object WIO extends AllBuilders[Eff, Ctx] {
    // Export the effect instance for use by WIO builders
    export ctx.effect

    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[Eff, In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Eff, Ctx, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[Eff, Ctx]

    def interruption: InterruptionBuilder.Step0[Eff, Ctx] = InterruptionBuilder.Step0[Eff, Ctx]()
  }
}

object WorkflowContext {
  private type AuxS[_S] = WorkflowContext { type State = _S }
  private type AuxE[_E] = WorkflowContext { type Event = _E }

  type State[T <: WorkflowContext] = T match {
    case AuxS[s] => s
  }
  type Event[T <: WorkflowContext] = T match {
    case AuxE[e] => e
  }

  /** AUX type including the effect type parameter */
  type AUX[St, Evt, F[_]] = WorkflowContext { type State = St; type Event = Evt; type Eff[A] = F[A] }

  /** Simplified AUX without effect - for backwards compatibility during migration */
  type AUX2[St, Evt] = WorkflowContext { type State = St; type Event = Evt }
}

/** Helper trait for synchronous (Id-based) workflow contexts. Extend this for workflows that run synchronously without an effect wrapper.
  */
trait SyncWorkflowContext extends WorkflowContext {
  type Eff[A] = cats.Id[A]
  given effect: Effect[Eff] = Effect.idEffect
}
