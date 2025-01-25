package workflows4s.wio

object TestCtx extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = String

  extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
    def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Ctx] = ActiveWorkflow(wio.provideInput(state), state)
  }

  def ignore[A, B, C]: (A, B) => C = (_, _) => ???

  given Conversion[String, SimpleEvent] = SimpleEvent.apply
}
