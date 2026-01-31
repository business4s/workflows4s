package workflows4s.wio

import cats.effect.IO
import workflows4s.wio.builders.{AllBuilders, InterruptionBuilder}

trait WorkflowContext { ctx: WorkflowContext =>
  type Event
  type State

  // Hardcoded to IO for now - will be made polymorphic in future PR
  type Eff[A] = IO[A]

  type Ctx = WorkflowContext.AUX[State, Event, Eff]

  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[Eff, In, Err, Out, Ctx]
  object WIO extends AllBuilders[Eff, Ctx] {
    export workflows4s.wio.WIO.{Branch as _, Draft as _, Initial as _, Interruption as _, *}

    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[Eff, In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Eff, Ctx, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[Eff, Ctx]

    def interruption: InterruptionBuilder.Step0[Eff, Ctx] = InterruptionBuilder.Step0[Eff, Ctx]()
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

  type AUX[St, Evt, F[_]] = WorkflowContext { type State = St; type Event = Evt; type Eff[A] = F[A] }
}
