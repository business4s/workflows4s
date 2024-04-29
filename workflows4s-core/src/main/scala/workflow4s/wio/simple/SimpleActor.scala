package workflow4s.wio.simple

import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.StrictLogging
import workflow4s.wio.*
import workflow4s.wio.ActiveWorkflow.ForCtx

import java.time.Clock
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

abstract class SimpleActor[State](clock: Clock)(implicit IORuntime: IORuntime) extends StrictLogging {

  type Ctx <: WorkflowContext
  // Its initialized to null because compile doesnt allow overriding vars, and it has to be like that to parametrize by type member (Context)
  // https://stackoverflow.com/questions/31398344/why-it-is-not-possible-to-override-mutable-variable-in-scala
  protected var wf: ActiveWorkflow.ForCtx[Ctx] = _
  def state: State                             = extractState(wf)
  protected def extractState(wf: ActiveWorkflow.ForCtx[Ctx]): State

  val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.SignalResponse[Resp] = {
    logger.debug(s"Handling signal ${req}")
    wf.handleSignal(signalDef)(req, clock.instant()) match {
      case Some(value) =>
        val (event, resp) = value.unsafeRunSync()
        saveEvent(event)
        handleEvent(event)
        SimpleActor.SignalResponse.Ok(resp)
      case None        =>
        logger.debug(s"Unexpected signal ${req}. Wf: ${wf.getDesc}")
        SimpleActor.SignalResponse.UnexpectedSignal(wf.getDesc)
    }
  }

  protected def handleEvent(event: WCEvent[Ctx], inRecovery: Boolean = false): Unit = {
    logger.debug(s"Handling event: ${event}")
    val resp = wf.handleEvent(event, clock.instant())
    resp match {
      case Some(newFlow) =>
        updateState(newFlow)
        if (!inRecovery) runIO()
      case None          =>
        if(!inRecovery) throw new IllegalArgumentException(s"Unexpected event ${event} at: ${wf.getDesc}")
        else logger.warn(s"Unexpected event ignored in recovery. Event ${event} at: ${wf.getDesc}")
    }
  }

  def runIO(): Unit = {
    logger.debug(s"Running IO}")
    wf.proceed(clock.instant()) match {
      case Some(eventIO) =>
        val event = eventIO.unsafeRunSync()
        saveEvent(event)
        handleEvent(event)
      case None =>
        logger.debug(s"No IO to run. Wf: ${wf.getDesc}")
    }
  }

  def saveEvent(e: WCEvent[Ctx]): Unit = {
    logger.debug(s"Saving event ${e}")
    events += e
  }

  private def updateState(newWf: ActiveWorkflow.ForCtx[Ctx]): Unit = {
    logger.debug(s"""Updating workflow
                    | New behaviour: ${newWf.getDesc}
                    | New state: ${newWf.state}""".stripMargin)
    wf = newWf
  }

  def recover(events: List[WCEvent[Ctx]]): Unit = {
    events.foreach(e => this.handleEvent(e, inRecovery = true))
    this.runIO()
  }

}

object SimpleActor {

  def create[Ctx0 <: WorkflowContext, In <: WCState[Ctx0]](
      behaviour: WIO[In, Nothing, WCState[Ctx0], Ctx0],
      state0: In,
      clock: Clock,
      knockerUpper: KnockerUpper,
  )(implicit
      ior: IORuntime,
  ): SimpleActor[WCState[Ctx0]] { type Ctx = Ctx0 } = {
    val activeWf: ActiveWorkflow.ForCtx[Ctx0] = ActiveWorkflow(behaviour, state0)(new Interpreter(knockerUpper))
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
      clock: Clock,
      knockerUpper: KnockerUpper,
  )(implicit
      ior: IORuntime,
  ): SimpleActor[WCState[Ctx0]] { type Ctx = Ctx0 } = {
    val activeWf: ActiveWorkflow.ForCtx[Ctx0] =
      ActiveWorkflow(behaviour.transformInput[Any](_ => input), state)(new Interpreter(knockerUpper))
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

}
