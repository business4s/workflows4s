package workflow4s.example.doobie

import workflow4s.wio.{KnockerUpper, WIO, WorkflowContext}
import workflows4s.doobie.EventCodec
import workflows4s.doobie.postgres.{PostgresRuntime, WorkflowId}

object PostgresExample {

  // doc_start
  object Ctx extends WorkflowContext {
    trait State
    trait Event
  }

  val knockerUpper: KnockerUpper.Factory[WorkflowId]   = ???
  val workflow: WIO[Any, Nothing, Ctx.State, Ctx.type] = ???
  val initialState: Ctx.State                          = ???
  val eventCodec: EventCodec[Ctx.Event]                = ???

  val runtime = PostgresRuntime(knockerUpper)
  runtime.runWorkflow[Ctx.type, Ctx.State](WorkflowId(1L), workflow, initialState, eventCodec)
  // doc_end

}
