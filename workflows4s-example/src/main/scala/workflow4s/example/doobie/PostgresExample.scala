package workflow4s.example.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
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
  val transactor: Transactor[IO]                       = ???

  val runtime = PostgresRuntime.default[Ctx.type, Ctx.State](workflow, eventCodec, transactor, knockerUpper)
  runtime.createInstance(WorkflowId(1L), ???)
  // doc_end

}
