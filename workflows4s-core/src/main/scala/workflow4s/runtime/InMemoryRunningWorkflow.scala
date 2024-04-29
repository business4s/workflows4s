package workflow4s.runtime

import cats.effect.{IO, Ref}
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxEitherId, toTraverseOps}
import workflow4s.runtime.RunningWorkflow.UnexpectedSignal
import workflow4s.wio.*

import java.time.Clock

// TODO current implementation is not safe in concurrent scenario. State should be locked for the duration of side effects
class InMemoryRunningWorkflow[Ctx <: WorkflowContext](
    stateRef: Ref[IO, ActiveWorkflow.ForCtx[Ctx]],
    eventsRef: Ref[IO, Vector[WCEvent[Ctx]]],
    clock: Clock,
) extends RunningWorkflow[IO, WCState[Ctx]] {

  def getEvents: IO[Vector[WCEvent[Ctx]]] = eventsRef.get

  override def queryState(): IO[WCState[Ctx]] = stateRef.get.map(_.state)

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): IO[Either[UnexpectedSignal, Resp]] = {
    for {
      state  <- stateRef.get
      now    <- IO(clock.instant())
      result <- state.handleSignal(signalDef)(req, now) match {
                  case Some(resultIO) =>
                    for {
                      result       <- resultIO
                      (event, resp) = result
                      _            <- handleEvent(event)
                    } yield resp.asRight
                  case None           => UnexpectedSignal(signalDef).asLeft.pure[IO]
                }
    } yield result
  }

  override def wakeup(): IO[Unit] =
    for {
      state <- stateRef.get
      now   <- IO(clock.instant())
      _     <- state.proceed(now) match {
                 case Some(resultIO) => resultIO.flatMap(handleEvent(_))
                 case None           => IO.unit
               }
    } yield ()

  private def handleEvent(event: WCEvent[Ctx], inRecovery: Boolean = false): IO[Unit] = {
    for {
      now        <- IO(clock.instant())
      state      <- stateRef.get
      newStateOpt = state.handleEvent(event, now)
      newState   <- IO.fromOption(newStateOpt)(new Exception("Event returned by signal handling was not handled"))
      _          <- eventsRef.update(_.appended(event))
      _          <- stateRef.set(newState)
      _          <- if (!inRecovery) wakeup() else IO.unit
    } yield ()
  }

  def recover(events: Seq[WCEvent[Ctx]]): IO[Unit] = events.toList.traverse(handleEvent(_, inRecovery = true)).void
}
