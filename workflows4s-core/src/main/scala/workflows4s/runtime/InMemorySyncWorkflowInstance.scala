package workflows4s.runtime

import cats.Id
import cats.effect.unsafe.IORuntime
import cats.implicits.catsSyntaxEitherId
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*

import java.time.Clock
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class InMemorySyncWorkflowInstance[Ctx <: WorkflowContext](
    initialState: ActiveWorkflow[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent.Curried,
)(implicit
    IORuntime: IORuntime,
) extends WorkflowInstance[Id, WCState[Ctx]]
    with StrictLogging {

  private var wf: ActiveWorkflow[Ctx]              = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]]                 = events.toList

  override def getProgress: WIOExecutionProgress[WCState[Ctx]] = wf.wio.toProgress

  override def queryState(): WCState[Ctx] = wf.liveState(clock.instant())

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Either[WorkflowInstance.UnexpectedSignal, Resp] = {
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
    logger.debug(s"Running IO")
    wf.proceed(clock.instant()) match {
      case Some(eventIO) =>
        val event = eventIO.unsafeRunSync()
        saveEvent(event)
        handleEvent(event)
      case None          =>
        logger.debug(s"""No IO to run. Wf:
                        |${wf.getDesc}""".stripMargin)
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

  private def updateState(newWf: ActiveWorkflow[Ctx]): Unit = {
    if (this.wf != newWf.wakeupAt) {
      knockerUpper.updateWakeup((), newWf.wakeupAt)
    }
    logger.debug(s"""Updating workflow. New behaviour:
                    | ${newWf.getDesc}
                    | New state: ${newWf.staticState}""".stripMargin)
    wf = newWf
  }

}
