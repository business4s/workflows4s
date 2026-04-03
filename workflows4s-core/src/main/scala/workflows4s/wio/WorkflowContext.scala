package workflows4s.wio

import cats.effect.IO
import workflows4s.wio.builders.AllBuilders

/** Defines the type-level environment for a workflow: its State and Event types.
  *
  * Extend this trait to create a workflow context, then use the inner `WIO` object as the entry point for building workflow steps with the builder
  * DSL.
  *
  * {{{
  * object MyCtx extends WorkflowContext {
  *   type Event = MyEvent
  *   type State = MyState
  * }
  *
  * val step: MyCtx.WIO[MyState, Nothing, MyState] = MyCtx.WIO.pure(...)
  * }}}
  */
trait WorkflowContext { ctx: WorkflowContext =>
  type Event
  type State
  type Ctx = WorkflowContext.AUX[State, Event]

  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[IO, In, Err, Out, Ctx]
  object WIO extends AllBuilders[IO, Ctx] {
    export workflows4s.wio.WIO.{Branch as _, Draft as _, Initial as _, Interruption as _, build as _, *}

    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[IO, In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[IO, Ctx, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[IO, Ctx]

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

  type AUX[St, Evt] = WorkflowContext { type State = St; type Event = Evt }
}
