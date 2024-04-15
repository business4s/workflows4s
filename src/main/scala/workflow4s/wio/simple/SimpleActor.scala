package workflow4s.wio.simple

import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.StrictLogging
import workflow4s.wio.Interpreter.{ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.*
import workflow4s.wio.ActiveWorkflow.ForCtx

import java.time.Clock

abstract class SimpleActor[State](clock: Clock)(implicit IORuntime: IORuntime) extends StrictLogging {

  type Ctx <: WorkflowContext
  // Its initialized to null because compile doesnt allow overriding vars, and it has to be like that to parametrize by type member (Context)
  // https://stackoverflow.com/questions/31398344/why-it-is-not-possible-to-override-mutable-variable-in-scala
  protected var wf: ActiveWorkflow.ForCtx[Ctx] = _
  def state: State                             = extractState(wf)
  protected def extractState(wf: ActiveWorkflow.ForCtx[Ctx]): State

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.SignalResponse[Resp] = {
    logger.debug(s"Handling signal ${req}")
    wf.handleSignal(signalDef)(req) match {
      case SignalResponse.Ok(value)          =>
        val (newWf, resp) = value.unsafeRunSync()
        wf = newWf
        logger.debug(s"Signal handled. Next state: ${newWf.getDesc}")
        proceed(runIO = true)
        SimpleActor.SignalResponse.Ok(resp)
      case SignalResponse.UnexpectedSignal() =>
        logger.debug(s"Unexpected signal ${req}. Wf: ${wf.getDesc}")
        SimpleActor.SignalResponse.UnexpectedSignal(wf.getDesc)
    }
  }

  protected def handleEvent(event: WCEvent[Ctx]): SimpleActor.EventResponse = {
    logger.debug(s"Handling event: ${event}")
    val resp = wf.handleEvent(event) match {
      case Interpreter.EventResponse.Ok(newFlow)       =>
        wf = newFlow
        proceed(false)
        SimpleActor.EventResponse.Ok
      case Interpreter.EventResponse.UnexpectedEvent() => SimpleActor.EventResponse.UnexpectedEvent(wf.getDesc)
    }
    logger.debug(s"Event response: ${resp}. New wf: ${wf.getDesc}")
    resp
  }

  def proceed(runIO: Boolean): Unit = {
    logger.debug(s"Proceeding to the next step. Run io: ${runIO}")
    wf.proceed(runIO, clock.instant()) match {
      case ProceedResponse.Executed(newFlowIO) =>
        wf = newFlowIO.unsafeRunSync()
        logger.debug(s"Proceeded. New wf: ${wf.getDesc}")
        proceed(runIO)
      case ProceedResponse.Noop()              =>
        logger.debug(s"Can't proceed. Wf: ${wf.getDesc}")
        ()
    }
  }

  def recover(): Unit = {
    wf.interpreter.journal
      .readEvents()
      .unsafeRunSync()
      .foreach(e =>
        this.handleEvent(e) match {
          case SimpleActor.EventResponse.Ok                    => ()
          case SimpleActor.EventResponse.UnexpectedEvent(desc) => throw new IllegalArgumentException(s"Unexpected event :${desc}")
        },
      )
    this.proceed(runIO = true)
  }

}

object SimpleActor {

  def create[Ctx0 <: WorkflowContext, In <: WCState[Ctx0]](
      behaviour: WIO[In, Nothing, WCState[Ctx0], Ctx0],
      state0: In,
      journalPersistance: JournalPersistance[WCEvent[Ctx0]],
      clock: Clock,
      knockerUpper: KnockerUpper
  )(implicit
      ior: IORuntime,
  ): SimpleActor[WCState[Ctx0]] = {
    val activeWf: ActiveWorkflow.ForCtx[Ctx0] = ActiveWorkflow(behaviour, state0)(new Interpreter(journalPersistance, knockerUpper))
    new SimpleActor[WCState[Ctx0]](clock) {
      override type Ctx = Ctx0
      wf = activeWf
      override protected def extractState(wf: ForCtx[Ctx0]): WCState[Ctx0] = wf.state
    }
  }

  // this might need to evolve, we provide initial state in case the input can't be one.
  // its needed because (theoretically) state can be queried before any succesfull execution.
  def createWithState[Ctx0 <: WorkflowContext, In](
      behaviour: WIO[In, Nothing, WCState[Ctx0], Ctx0],
      input: In,
      state: WCState[Ctx0],
      journalPersistance: JournalPersistance[WCEvent[Ctx0]],
      clock: Clock,
      knockerUpper: KnockerUpper
  )(implicit
      ior: IORuntime,
  ): SimpleActor[WCState[Ctx0]] = {
    val activeWf: ActiveWorkflow.ForCtx[Ctx0] = ActiveWorkflow(behaviour.transformInput[Any](_ => input), state)(new Interpreter(journalPersistance, knockerUpper))
    new SimpleActor[WCState[Ctx0]](clock) {
      override type Ctx = Ctx0
      wf = activeWf
      override protected def extractState(wf: ForCtx[Ctx0]): WCState[Ctx0] = wf.state
    }
  }

  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Ok[Resp](result: Resp)        extends SignalResponse[Resp]
    case class UnexpectedSignal(msg: String) extends SignalResponse[Nothing]
  }

  sealed trait EventResponse
  object EventResponse {
    case object Ok                          extends EventResponse
    case class UnexpectedEvent(msg: String) extends EventResponse
  }

  sealed trait QueryResponse[+Resp]
  object QueryResponse {
    case class Ok[Resp](result: Resp)       extends QueryResponse[Resp]
    case class UnexpectedQuery(msg: String) extends QueryResponse[Nothing]
  }
}
