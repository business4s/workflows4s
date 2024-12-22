package workflows4s.wio

import java.time.Instant

import scala.util.chaining.scalaUtilChainingOps

import cats.effect.IO
import workflows4s.wio.Interpreter.SignalResponse
import workflows4s.wio.internal.*

abstract class ActiveWorkflow[Ctx <: WorkflowContext] {
  type CurrentState <: WCState[Ctx]
  val state: CurrentState
  def wio: WIO[CurrentState, Nothing, WCState[Ctx], Ctx]
  def wakeupAt: Option[Instant]

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req, now: Instant): Option[IO[(WCEvent[Ctx], Resp)]] = {
    effectlessProceed(now)
      .getOrElse(this)
      .pipe(x => SignalEvaluator.handleSignal(signalDef, req, x.wio, x.state)) match {
      case SignalResponse.Ok(value)          => Some(value)
      case SignalResponse.UnexpectedSignal() => None
    }
  }

  def handleEvent(event: WCEvent[Ctx], now: Instant): Option[ActiveWorkflow[Ctx]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    EventEvaluator
      .handleEvent(event, wf.wio, wf.state)
      .newWorkflow
      .map(x => x.effectlessProceed(now).getOrElse(x))
  }

  def proceed(now: Instant): Option[IO[WCEvent[Ctx]]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    RunIOEvaluator.proceed(wf.wio, wf.state, now).event
  }

  // moves forward as far as possible
  private def effectlessProceed(now: Instant): Option[ActiveWorkflow[Ctx]] =
    ProceedEvaluator
      .proceed(wio, state, now)
      .newFlow
      .map(x => x.effectlessProceed(now).getOrElse(x))

  def getDesc: String = CurrentStateEvaluator.getCurrentStateDescription(wio)
}

object ActiveWorkflow {

  def apply[Ctx <: WorkflowContext, In <: WCState[Ctx]](
      wio0: WIO[In, Nothing, WCState[Ctx], Ctx],
      value0: In,
      wakeupAt0: Option[Instant],
  ): ActiveWorkflow[Ctx] =
    new ActiveWorkflow[Ctx] {
      override type CurrentState = In
      override val state: CurrentState                                = value0
      override def wio: WIO[CurrentState, Nothing, WCState[Ctx], Ctx] = wio0
      override def wakeupAt: Option[Instant]                          = wakeupAt0
    }

  sealed trait ProceedResponse[Ctx <: WorkflowContext]
  object ProceedResponse {
    case class Event[Ctx <: WorkflowContext](eventIO: IO[WCEvent[Ctx]]) extends ProceedResponse[Ctx]
    case class Noop[Ctx <: WorkflowContext]()                           extends ProceedResponse[Ctx]
  }

}
