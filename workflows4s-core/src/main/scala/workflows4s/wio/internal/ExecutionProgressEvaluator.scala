package workflows4s.wio.internal

import cats.syntax.all.*
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta}
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress.Dynamic
object ExecutionProgressEvaluator {

  def run[Ctx <: WorkflowContext, In](
      wio: WIO[In, ?, ?, Ctx],
      input: Option[In],
      lastSeenState: Option[WCState[Ctx]],
  ): WIOExecutionProgress[WCState[Ctx]] = {
    new ExecProgressVisitor(wio, None, lastSeenState, input).run
  }

  private class ExecProgressVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      result: WIOExecutionProgress.ExecutionResult[WCState[Ctx]],
      lastSeenState: Option[WCState[Ctx]],
      input: Option[In],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = WIOExecutionProgress[WCState[Ctx]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                     = {
      val meta = WIOMeta.HandleSignal(wio.meta.signalName, wio.meta.operationName, wio.meta.error.toModel)
      WIOExecutionProgress.HandleSignal(meta, result)
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = {
      val meta = WIOMeta.RunIO(wio.meta.name, wio.meta.error.toModel)
      WIOExecutionProgress.RunIO(meta, result)
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = {
      WIOExecutionProgress.Sequence(Seq(recurse(wio.base, input, None), WIOExecutionProgress.Dynamic(WIOMeta.Dynamic(wio.errorMeta.toModel))))
    }
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          =
      recurse(wio.base, input.map(wio.contramapInput))
    def onNoop(wio: WIO.End[Ctx]): Result                                                                              = WIOExecutionProgress.End(result)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      WIOExecutionProgress.HandleError(
        recurse(wio.base, input, None),
        WIOExecutionProgress.Dynamic(WIOMeta.Dynamic(wio.newErrorMeta.toModel)),
        WIOMeta.HandleError(wio.newErrorMeta.toModel, wio.handledErrorMeta.toModel),
        result,
      )
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      WIOExecutionProgress.HandleError(
        recurse(wio.base, input, result = None),
        recurse(wio.handleError, None, result = None),
        WIOMeta.HandleError(wio.newErrorMeta.toModel, wio.handledErrorMeta.toModel),
        result,
      )
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      (recurse(wio.first, None, result = None), recurse(wio.second, None, result = None)) match {
        case (WIOExecutionProgress.Sequence(steps1), WIOExecutionProgress.Sequence(steps2)) => WIOExecutionProgress.Sequence(steps1 ++ steps2)
        case (x, WIOExecutionProgress.Sequence(steps2))                                     => WIOExecutionProgress.Sequence(List(x) ++ steps2)
        case (WIOExecutionProgress.Sequence(steps1), x)                                     => WIOExecutionProgress.Sequence(steps1 ++ List(x))
        case (a, b)                                                                         => WIOExecutionProgress.Sequence(List(a, b))
      }
    }

    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                             = WIOExecutionProgress.Pure(WIOMeta.Pure(wio.meta.name, wio.meta.error.toModel), result)
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result = {
      WIOExecutionProgress.Loop(
        recurse(wio.loop, None, result = None).toModel,
        wio.onRestart.map(recurse(_, None, result = None).toModel),
        WIOMeta.Loop(wio.meta.conditionName, wio.meta.releaseBranchName, wio.meta.restartBranchName),
        (
          if (!wio.current.asExecuted.isDefined) wio.history.appended(wio.current)
          else wio.history
        ).map(recurse(_, input, result = None)),
      )
    }

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                             = {
      WIOExecutionProgress.Fork(
        wio.branches.map(x => recurse(x.wio(), input.flatMap(x.condition), result = None)),
        WIOMeta.Fork(wio.name, wio.branches.map(x => WIOMeta.Branch(x.name))),
        wio.selected,
      )
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      // We could express embedding in model but need a use case for it.
      val visitor = new ExecProgressVisitor(
        wio.inner,
        this.result.flatMap(_.traverse(wio.embedding.unconvertState)),
        lastSeenState.flatMap(wio.embedding.unconvertState),
        input,
      )
      visitor.run.map(x => input.map(wio.embedding.convertState(x, _))) // get is unsafe
      // but it should always be present.
      // if we got state inside, also the last seen state should be correct
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      val (trigger, rest) = extractFirstInterruption(recurse(wio.interruption, lastSeenState, result = None))
        .getOrElse(throw new Exception(s"""Couldn't extract interruption from the interruption path. This is a bug, please report it.
                                          |Workflow: $wio""".stripMargin))
      WIOExecutionProgress.Interruptible(
        recurse(wio.base, input, result = None),
        trigger,
        rest,
        result,
      )
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = WIOExecutionProgress.Timer(
      WIOMeta
        .Timer(
          wio.duration match {
            case DurationSource.Static(duration)     => duration.some
            case DurationSource.Dynamic(getDuration) => input.map(getDuration)
          },
          None,
          wio.name,
        ),
      result,
    )

    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result =
      WIOExecutionProgress.Timer(WIOMeta.Timer(None, wio.resumeAt.some, None), result) // TODO persist duration and name
    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = recurse(wio.original, wio.input.some, wio.output.some)
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result           = recurse(wio.original, wio.input.some, None)

    def onParallel[InterimState <: workflows4s.wio.WorkflowContext.State[Ctx]](wio: WIO.Parallel[Ctx, In, Err, Out, InterimState]): Result = {
      WIOExecutionProgress.Parallel(wio.elements.map(elem => recurse(elem.wio, input, result = None)), result)
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): WIOExecutionProgress[WCState[Ctx]] = {
      WIOExecutionProgress.Checkpoint(recurse(wio.base, input, result = None), result)
    }

    override def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): WIOExecutionProgress[WCState[Ctx]] =
      WIOExecutionProgress.Recovery(result)

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[I1, E1, O1, Ctx],
        input: Option[I1],
        result: WIOExecutionProgress.ExecutionResult[WCState[Ctx]] = this.result,
    ): WIOExecutionProgress[WCState[Ctx]] = {
      val state = result.flatMap(_.toOption).orElse(lastSeenState)
      new ExecProgressVisitor(wio, result, state, input).run
    }

    extension (m: ErrorMeta[?]) {
      def toModel: Option[WIOMeta.Error] = m match {
        case ErrorMeta.NoError()     => None
        case ErrorMeta.Present(name) => WIOMeta.Error(name).some
      }
    }

  }

  // TODO this whole method should be stricter, it makes assumptions (e.g. interruption cant be wrapped in parallel)
  //  and should fail if those assumptions don't hold
  def extractFirstInterruption[S](flow: WIOExecutionProgress[S]): Option[(WIOExecutionProgress.Interruption[S], Option[WIOExecutionProgress[S]])] = {
    flow match {
      case WIOExecutionProgress.Sequence(steps)                               =>
        extractFirstInterruption(steps.head).map((first, rest) =>
          (
            first,
            rest match {
              case Some(value) => WIOExecutionProgress.Sequence(steps.toList.updated(0, value)).some
              case None        =>
                if (steps.size > 3) WIOExecutionProgress.Sequence(steps.tail).some
                else steps(1).some
            },
          ),
        )
      case WIOExecutionProgress.Dynamic(_)                                    => None
      case WIOExecutionProgress.RunIO(_, _)                                   => None
      case x @ WIOExecutionProgress.HandleSignal(_, _)                        => Some((x, None))
      case WIOExecutionProgress.HandleError(base, handler, errorName, result) =>
        // TODO this is not a correct model, in case of signal handler with error handler,
        //  it will not express it correctly
        extractFirstInterruption(base).map((first, rest) => first -> rest.map(x => WIOExecutionProgress.HandleError(x, handler, errorName, result)))
      case _ @WIOExecutionProgress.End(_)                                     => None
      case _ @WIOExecutionProgress.Pure(_, _)                                 => None
      case _: WIOExecutionProgress.Loop[?]                                    => None
      case _ @WIOExecutionProgress.Fork(_, _, _)                              => None
      case x @ WIOExecutionProgress.Timer(_, _)                               => (x, None).some
      case WIOExecutionProgress.Interruptible(_, _, _, _)                     => None
      case _: WIOExecutionProgress.Parallel[?]                                => None
      case _: WIOExecutionProgress.Checkpoint[?]                              => None
      case _: WIOExecutionProgress.Recovery[?]                                => None
    }
  }

}
