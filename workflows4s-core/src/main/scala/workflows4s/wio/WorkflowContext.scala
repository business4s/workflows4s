package workflows4s.wio

import workflows4s.wio.builders.{AllBuilders, InterruptionBuilder}

trait WorkflowContext { ctx: WorkflowContext =>
  type Event
  type State
  type Ctx = WorkflowContext.AUX[State, Event]

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
  type AuxS[_S]                    = WorkflowContext { type State = _S }
  type AuxE[_E]                    = WorkflowContext { type Event = _E }
  type State[T <: WorkflowContext] = T match {
    case AuxS[s] => s
  }
  type Event[T <: WorkflowContext] = T match {
    case AuxE[s] => s
  }

  type AUX[St, Evt] = WorkflowContext { type State = St; type Event = Evt }
}
