package workflows4s.wio

import cats.effect.IO
import workflows4s.wio.Interpreter.SignalResponse
import workflows4s.wio.internal.*

import java.time.Instant
import scala.util.chaining.scalaUtilChainingOps

abstract class ActiveWorkflow {
  type Context <: WorkflowContext
  type CurrentState <: WCState[Context]
  type Error = Any
  val state: CurrentState
  def wio: WIO[CurrentState, Nothing, WCState[Context], Context]
  def interpreter: Interpreter

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req, now: Instant): Option[IO[(WCEvent[Context], Resp)]] = {
    effectlessProceed(now)
      .getOrElse(this)
      .pipe(x => SignalEvaluator.handleSignal(signalDef, req, x.wio, x.state)) match {
      case SignalResponse.Ok(value)          => Some(value)
      case SignalResponse.UnexpectedSignal() => None
    }
  }

  def handleEvent(event: WCEvent[Context], now: Instant): Option[ActiveWorkflow.ForCtx[Context]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    EventEvaluator
      .handleEvent(event, wf.wio, wf.state, interpreter)
      .newWorkflow
      .map(x => x.effectlessProceed(now).getOrElse(x))
  }

  // moves forward as far as possible
  private def effectlessProceed(now: Instant): Option[ActiveWorkflow.ForCtx[Context]] =
    ProceedEvaluator
      .proceed(wio, state, interpreter, now)
      .newFlow
      .map(x => x.effectlessProceed(now).getOrElse(x))

  def proceed(now: Instant): Option[IO[WCEvent[Context]]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    RunIOEvaluator.proceed(wf.wio, wf.state, interpreter, now).event
  }

  def getDesc: String = CurrentStateEvaluator.getCurrentStateDescription(wio)
}

object ActiveWorkflow {

  type ForCtx[Ctx] = ActiveWorkflow { type Context = Ctx }

  def apply[Ctx <: WorkflowContext, In <: WCState[Ctx]](wio0: WIO[In, Nothing, WCState[Ctx], Ctx], value0: In)(
      interpreter0: Interpreter,
  ): ActiveWorkflow.ForCtx[Ctx] =
    new ActiveWorkflow {
      override type Context      = Ctx
      override type CurrentState = In
      override val state: CurrentState                                    = value0
      override def wio: WIO[CurrentState, Nothing, WCState[Context], Ctx] = wio0
      override def interpreter: Interpreter                               = interpreter0
    }

  sealed trait ProceedResponse[Ctx <: WorkflowContext]
  object ProceedResponse {
    case class NewFlow[Ctx <: WorkflowContext](wf: ActiveWorkflow.ForCtx[Ctx]) extends ProceedResponse[Ctx]
    case class Event[Ctx <: WorkflowContext](eventIO: IO[WCEvent[Ctx]])        extends ProceedResponse[Ctx]
    case class Noop[Ctx <: WorkflowContext]()                                  extends ProceedResponse[Ctx]
  }
}
