package workflows4s.wio

import cats.effect.IO
import workflows4s.wio.Interpreter.SignalResponse
import workflows4s.wio.internal.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

case class ActiveWorkflow[Ctx <: WorkflowContext](wio: WIO.Initial[Ctx], initialState: WCState[Ctx]) {
  lazy val wakeupAt: Option[Instant] = GetWakeupEvaluator.extractNearestWakeup(wio)

  lazy val staticState: WCState[Ctx] = GetStateEvaluator.extractLastState(wio, (), initialState).getOrElse(initialState)

  def liveState(now: Instant): WCState[Ctx] = {
    val wf = effectlessProceed(now).getOrElse(this)
    wf.staticState
  }

  def liveSignals(now: Instant): List[SignalDef[?, ?]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    GetSignalDefsEvaluator.run(wf.wio)
  }

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req, now: Instant): Option[IO[(WCEvent[Ctx], Resp)]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    SignalEvaluator.handleSignal(signalDef, req, wf.wio, wf.staticState) match {
      case SignalResponse.Ok(value)          => Some(value)
      case SignalResponse.UnexpectedSignal() => None
    }
  }

  def handleEvent(event: WCEvent[Ctx], now: Instant): Option[ActiveWorkflow[Ctx]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    EventEvaluator
      .handleEvent(event, wf.wio, initialState)
      .newWorkflow
      .map(newWio => this.copy(wio = newWio))
      .map(x => x.effectlessProceed(now).getOrElse(x))
  }

  def proceed(now: Instant): Option[IO[Either[Instant, WCEvent[Ctx]]]] = {
    val wf = effectlessProceed(now).getOrElse(this)
    RunIOEvaluator.proceed(wf.wio, wf.staticState, now).event
  }

  def progress(now: Instant): WIOExecutionProgress[WCState[Ctx]] = effectlessProceed(now).getOrElse(this).wio.toProgress

  // moves forward as far as possible
  private def effectlessProceed(now: Instant): Option[ActiveWorkflow[Ctx]] =
    ProceedEvaluator
      .proceed(wio, initialState, now)
      .newFlow
      .map(newWio => this.copy(wio = newWio))
      .map(x => x.effectlessProceed(now).getOrElse(x))
}
