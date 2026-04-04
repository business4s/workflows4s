package workflows4s.testing.cats.effect

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.*
import workflows4s.runtime.cats.effect.{InMemoryConcurrentRuntime, InMemoryConcurrentWorkflowInstance}
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.wio.*

case class InMemoryConcurrentTestRuntimeAdapter[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val runtime = InMemoryConcurrentRuntime.default[IO, Ctx](workflow, state, engine).unsafeRunSync()
    Actor(List(), runtime)
  }

  override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

  case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemoryConcurrentRuntime[IO, Ctx])
      extends DelegateWorkflowInstance[Id, WCState[Ctx]]
      with TestRuntimeAdapter.EventIntrospection[WCEvent[Ctx]] {
    val base: InMemoryConcurrentWorkflowInstance[IO, Ctx] = {
      val inst = runtime.createInstance("").unsafeRunSync()
      inst.recover(events).unsafeRunSync()
      inst
    }
    val delegate: WorkflowInstance[Id, WCState[Ctx]]      = MappedWorkflowInstance(base, [t] => (x: IO[t]) => x.unsafeRunSync())

    override def getEvents: Seq[WCEvent[Ctx]]                                                         = base.getEvents.unsafeRunSync()
    override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
      delegate.getExpectedSignals(includeRedeliverable)
  }

}
