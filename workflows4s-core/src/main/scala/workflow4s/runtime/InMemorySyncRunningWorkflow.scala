package workflow4s.runtime

import cats.Id
import cats.effect.unsafe.IORuntime
import cats.implicits.catsSyntaxEitherId
import com.typesafe.scalalogging.StrictLogging
import workflow4s.runtime.RunningWorkflow.UnexpectedSignal
import workflow4s.wio.{ActiveWorkflow, SignalDef, WCEvent, WCState, WorkflowContext}

import java.time.Clock
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class InMemorySyncRunningWorkflow[Ctx <: WorkflowContext](initialState: ActiveWorkflow.ForCtx[Ctx], clock: Clock)(implicit
    IORuntime: IORuntime,
) extends RunningWorkflow[Id, WCState[Ctx]]
    with StrictLogging {

  private var wf: ActiveWorkflow.ForCtx[Ctx] = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]]   = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]] = events.toList


  override def queryState(): WCState[Ctx] = wf.state

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Either[RunningWorkflow.UnexpectedSignal, Resp] = {
    logger.debug(s"Handling signal ${req}")
    wf.handleSignal(signalDef)(req, clock.instant()) match {
      case Some(value) =>
        val (event, resp) = value.unsafeRunSync()
        saveEvent(event)
        handleEvent(event)
        resp.asRight
      case None        =>
        logger.debug(s"Unexpected signal ${req}. Wf: ${wf.getDesc}")
        UnexpectedSignal(signalDef).asLeft
    }
  }

  override def wakeup(): Unit = {
    logger.debug(s"Running IO}")
    wf.proceed(clock.instant()) match {
      case Some(eventIO) =>
        val event = eventIO.unsafeRunSync()
        saveEvent(event)
        handleEvent(event)
      case None          =>
        logger.debug(s"No IO to run. Wf: ${wf.getDesc}")
    }
  }

  def recover(events: Seq[WCEvent[Ctx]]): Unit = {
    events.foreach(e => this.handleEvent(e, inRecovery = true))
    this.wakeup()
  }

  private def handleEvent(event: WCEvent[Ctx], inRecovery: Boolean = false): Unit = {
    logger.debug(s"Handling event: ${event}")
    val resp = wf.handleEvent(event, clock.instant())
    resp match {
      case Some(newFlow) =>
        updateState(newFlow)
        if (!inRecovery) wakeup()
      case None          =>
        if (!inRecovery) throw new IllegalArgumentException(s"Unexpected event ${event} at: ${wf.getDesc}")
        else logger.warn(s"Unexpected event ignored in recovery. Event ${event} at: ${wf.getDesc}")
    }
  }

  private def saveEvent(e: WCEvent[Ctx]): Unit = {
    logger.debug(s"Saving event ${e}")
    events += e
  }

  private def updateState(newWf: ActiveWorkflow.ForCtx[Ctx]): Unit = {
    logger.debug(s"""Updating workflow
                    | New behaviour: ${newWf.getDesc}
                    | New state: ${newWf.state}""".stripMargin)
    wf = newWf
  }

}
