package workflows4s.wio

import cats.{Applicative, Functor, MonadThrow}
import workflows4s.wio.builders.AllBuilders

/** Defines the type-level environment for a workflow: its State, Event, and effect types.
  *
  * Extend this trait to create a workflow context, then use the inner `WIO` object as the entry point for building workflow steps with the builder
  * DSL.
  *
  * {{{
  * object MyCtx extends WorkflowContext {
  *   type Effect = IO
  *   type Event = MyEvent
  *   type State = MyState
  * }
  *
  * val step: MyCtx.WIO[MyState, Nothing, MyState] = MyCtx.WIO.pure(...)
  * }}}
  */
trait WorkflowContext { ctx: WorkflowContext =>
  type Effect[_]
  type Event
  type State
  type Ctx = WorkflowContext.AUX[State, Event, Effect]

  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[In, Err, Out, Ctx]
  object WIO extends AllBuilders[Ctx] {
    export workflows4s.wio.WIO.{Branch as _, Draft as _, Initial as _, Interruption as _, build as _, *}

    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Ctx, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[Ctx]

  }
}

object WorkflowContext {
  private type AuxS[_S]             = WorkflowContext { type State = _S }
  private type AuxE[_E]             = WorkflowContext { type Event = _E }
  type AuxEff[_F[_]] >: WorkflowContext { type Effect[T] = _F[T] } <: WorkflowContext { type Effect[T] = _F[T] }
  type State[T <: WorkflowContext]  = T match {
    case AuxS[s] => s
  }
  type Event[T <: WorkflowContext]  = T match {
    case AuxE[s] => s
  }
  type Effect[T <: WorkflowContext] = [A] =>> T match {
    case AuxEff[f] => f[A]
  }

  type AUX[St, Evt, Eff[_]] = WorkflowContext { type State = St; type Event = Evt; type Effect[T] = Eff[T] }
}
