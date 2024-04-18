package workflow4s.wio.internal

import cats.data.Ior
import cats.effect.IO
import cats.syntax.all.*
import workflow4s.wio.Interpreter.ProceedResponse
import workflow4s.wio.*

import java.time.Instant

object ProceedEvaluator {
  import NextWfState.{NewBehaviour, NewValue}

  // runIO required to eliminate Pures showing up after FlatMap
  def proceed[Ctx <: WorkflowContext, StIn <: WCState[Ctx]](
      wio: WIO[StIn, Nothing, WCState[Ctx], Ctx],
      state: StIn,
      runIO: Boolean,
      interpreter: Interpreter[Ctx],
      now: Instant,
  ): ProceedResponse[Ctx] = {
    val visitor = new ProceedVisitor(wio, state, runIO, interpreter.journal, state, now, interpreter.knockerUpper)
    visitor.run match {
      case Some(value) => ProceedResponse.Executed(value.map(wf => wf.toActiveWorkflow(interpreter)))
      case None        => ProceedResponse.Noop()
    }

  }

  private class ProceedVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      state: In,
      runIO: Boolean,
      journal: JournalPersistance.Write[WCEvent[Ctx]],
      initialState: WCState[Ctx],
      now: Instant,
      knockerUpper: KnockerUpper,
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    type NewWf           = NextWfState[Ctx, Err, Out]
    override type Result = Option[IO[NewWf]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                     = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = {
      if (runIO) {
        (for {
          evt <- wio.buildIO(state)
          _   <- journal.save(wio.evtHandler.convert(evt))
        } yield NewValue(wio.evtHandler.handle(state, evt))).some
      } else None
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = {
      val x: Option[IO[NextWfState[Ctx, Err1, Out1]]] = recurse(wio.base, state)
      x.map(_.map(preserveFlatMap(wio, _)))
    }
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                           = {
      recurse(wio.base, wio.contramapInput(state)).map(_.map(preserveMap(wio, _, state)))
    }
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                             = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base, state) // TODO, should name be preserved?
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      recurse(wio.base, state).map(_.map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }]
        applyHandleError(wio, casted, state)
      }))
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      recurse(wio.base, state).map(_.map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state)
      }))
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      recurse(wio.first, state).map(_.map(preserveAndThen(wio, _)))
    }
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                               = Some(NewValue(wio.value(state)).pure[IO])
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                   = {
      recurse(wio.current, state).map(_.map((newWf: NextWfState[Ctx, Err, Out1]) => {
        applyLoop(wio, newWf)
      }))
    }
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                               =
      selectMatching(wio, state).flatMap(nextWio => recurse(nextWio, state))

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newJournal                  = journal.contraMap(wio.embedding.convertEvent)
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(initialState)
          .getOrElse(
            wio.initialState(state),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      new ProceedVisitor(wio.inner, state, runIO, newJournal, newState, now, knockerUpper).run
        .map(_.map(convertResult(wio, _, state)))
    }

    // proceed on interruption will be needed for timeouts
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      def runBase: Result          = recurse(wio.base, state)
        .map(_.map(preserveHandleInterruption(wio.interruption, _)))
      val dedicatedProceed: Result = wio.interruption.trigger match {
        case x @ WIO.HandleSignal(_, _, _, _)       => None /// no proceeding when waiting for signal
        // type params are not correct
        case x: WIO.Timer[Ctx, In, Err, Out]        =>
          runTimer(x).map(awaitTimeIO =>
            for {
              awaitTime      <- awaitTimeIO
              mainFlowOut    <- recurse(wio.base, state) match {
                                  case Some(value) => value
                                  case None        => NewBehaviour(wio.base.transformInput[Any](_ => state), initialState.asRight).pure[IO]
                                }
              newInterruption = WIO.Interruption(awaitTime, wio.interruption.buildFinal)
            } yield preserveHandleInterruption(newInterruption, mainFlowOut),
          )
        // type params are not correct
        case x: WIO.AwaitingTime[Ctx, In, Err, Out] =>
          runAwaitingTime(x) match {
            case Left(newAwaitingIOOpt) =>
              newAwaitingIOOpt.map(awaitTimeIO =>
                for {
                  awaitTime      <- awaitTimeIO
                  mainFlowOut    <- recurse(wio.base, state) match {
                                      case Some(value) => value
                                      case None        =>
                                        NewBehaviour(wio.base.transformInput[Any](_ => state), initialState.asRight).pure[IO]
                                    }
                  newInterruption = WIO.Interruption(awaitTime, wio.interruption.buildFinal)
                } yield preserveHandleInterruption(newInterruption, mainFlowOut),
              )
            case Right(newWf)           =>
              // we ignore result of interpreting just interruption, we instead go with the whole interruption path
              // and replace the whole result with it.
              recurse(wio.interruption.finalWIO, initialState)
          }
      }
      dedicatedProceed.orElse(runBase)
    }

    private def runTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Option[IO[WIO.AwaitingTime[Ctx, In, Err, Out]]] = {
      Option.when(runIO) {
        val started                                           = WIO.Timer.Started(now)
        val releaseTime                                       = wio.getReleaseTime(started, state)
        val newBehaviour: WIO.AwaitingTime[Ctx, In, Err, Out] = WIO.AwaitingTime(releaseTime, wio.onRelease, wakeupRegistered = false)
        (for {
          _ <- journal.save(wio.eventHandler.convert(WIO.Timer.Started(now)))
        } yield newBehaviour)
      }
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = {
      runTimer(wio).map(_.map(NextWfState.NewBehaviour(_, initialState.asRight)))
    }

    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = {
      runAwaitingTime(wio) match {
        case Left(newAwaitingIOOpt) => newAwaitingIOOpt.map(_.map(x => NewBehaviour(x, initialState.asRight)))
        case Right(newWf)           => newWf.pure[IO].some
      }
    }

    def runAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Either[Option[IO[WIO.AwaitingTime[Ctx, In, Err, Out]]], NewWf] = {
      if (now.plusNanos(1).isAfter(wio.resumeAt)) NewValue(wio.onRelease(state)).asRight
      else {
        if (!wio.wakeupRegistered) {
          (for {
            _ <- knockerUpper.registerWakeup(wio.resumeAt)
          } yield wio.copy(wakeupRegistered = true)).some.asLeft
        } else None.asLeft
      }
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1): Option[IO[NextWfState[Ctx, E1, O1]]] =
      new ProceedVisitor(wio, s, runIO, journal, initialState, now, knockerUpper).run
  }

}
