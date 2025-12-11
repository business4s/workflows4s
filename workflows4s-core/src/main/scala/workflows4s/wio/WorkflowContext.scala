package workflows4s.wio

import workflows4s.wio.builders.{AllBuilders, InterruptionBuilder}

trait WorkflowContext { ctx: WorkflowContext =>
  type Event
  type State
  type F[_]
  type Ctx = WorkflowContext.AUX[State, Event, F]

  // HasEffect[Ctx] is derived via macro from HasEffect.derived
  // Don't provide a manual given here - it shadows the macro and loses type refinement

  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[In, Err, Out, Ctx]
  object WIO extends AllBuilders[Ctx] {
    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Ctx, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[Ctx]

    def interruption: InterruptionBuilder.Step0[Ctx] = InterruptionBuilder.Step0[Ctx]()
  }
}

object WorkflowContext {
  private type AuxS[_S]            = WorkflowContext { type State = _S }
  private type AuxE[_E]            = WorkflowContext { type Event = _E }
  type State[T <: WorkflowContext] = T match {
    case AuxS[s] => s
  }
  type Event[T <: WorkflowContext] = T match {
    case AuxE[s] => s
  }
  // Note: Effect/F cannot be extracted via match types (HKT limitation).
  // Use HasEffect[Ctx] macro instead for type-safe effect access.

  type AUX[St, Evt, F0[_]] = WorkflowContext { type State = St; type Event = Evt; type F[A] = F0[A] }
}
