package workflows4s.wio.internal

import cats.syntax.all.*
import workflows4s.wio.*
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.model.WIOExecutionProgress.ExecutedResult
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta}

object ExecutionProgressEvaluator {

  /** Now generic over F to match the WIO signature.
    */
  def run[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
      input: Option[In],
      lastSeenState: Option[WCState[Ctx]],
  ): WIOExecutionProgress[WCState[Ctx]] = {
    new ExecProgressVisitor[F, Ctx, In, Err, Out](wio, None, lastSeenState, input).run
  }

  private class ExecProgressVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
      result: WIOExecutionProgress.ExecutionResult[WCState[Ctx]],
      lastSeenState: Option[WCState[Ctx]],
      input: Option[In],
  ) extends Visitor[F, Ctx, In, Err, Out](wio) {

    override type Result = WIOExecutionProgress[WCState[Ctx]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result = {
      val meta = WIOMeta.HandleSignal(wio.meta.signalName, wio.meta.operationName, wio.meta.error.toModel)
      WIOExecutionProgress.HandleSignal(meta, result)
    }

    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result = {
      val meta = WIOMeta.RunIO(wio.meta.name, wio.meta.error.toModel, wio.meta.description)
      WIOExecutionProgress.RunIO(meta, result)
    }

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result = {
      WIOExecutionProgress.Sequence(Seq(recurse(wio.base, input, None), WIOExecutionProgress.Dynamic(WIOMeta.Dynamic(wio.errorMeta.toModel))))
    }

    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base, input.map(wio.contramapInput))

    def onNoop(wio: WIO.End[F, Ctx]): Result = WIOExecutionProgress.End(result)

    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      WIOExecutionProgress.HandleError(
        recurse(wio.base, input, None),
        WIOExecutionProgress.Dynamic(WIOMeta.Dynamic(wio.newErrorMeta.toModel)),
        WIOMeta.HandleError(wio.newErrorMeta.toModel, wio.handledErrorMeta.toModel),
        result,
      )
    }

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result = {
      WIOExecutionProgress.HandleError(
        recurse(wio.base, input, result = None),
        recurse(wio.handleError, None, result = None),
        WIOMeta.HandleError(wio.newErrorMeta.toModel, wio.handledErrorMeta.toModel),
        result,
      )
    }

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result = {
      (recurse(wio.first, None, result = None), recurse(wio.second, None, result = None)) match {
        case (WIOExecutionProgress.Sequence(steps1), WIOExecutionProgress.Sequence(steps2)) => WIOExecutionProgress.Sequence(steps1 ++ steps2)
        case (x, WIOExecutionProgress.Sequence(steps2))                                     => WIOExecutionProgress.Sequence(List(x) ++ steps2)
        case (WIOExecutionProgress.Sequence(steps1), x)                                     => WIOExecutionProgress.Sequence(steps1 ++ List(x))
        case (a, b)                                                                         => WIOExecutionProgress.Sequence(List(a, b))
      }
    }

    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result = WIOExecutionProgress.Pure(WIOMeta.Pure(wio.meta.name, wio.meta.error.toModel), result)

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result = {
      WIOExecutionProgress.Loop(
        recurse(wio.body, None, result = None).toModel,
        recurse(wio.onRestart, None, result = None).toModel.some,
        WIOMeta.Loop(wio.meta.conditionName, wio.meta.releaseBranchName, wio.meta.restartBranchName),
        (
          if wio.current.wio.asExecuted.isEmpty then wio.history.appended(wio.current.wio)
          else wio.history
        ).map(recurse(_, input, result = None)),
      )
    }

    def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result = {
      WIOExecutionProgress.Fork(
        wio.branches.map(x => recurse(x.wio, input.flatMap(x.condition), result = None)),
        WIOMeta.Fork(wio.name, wio.branches.map(x => WIOMeta.Branch(x.name))),
        wio.selected,
      )
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val visitor = new ExecProgressVisitor(
        wio.inner,
        this.result.flatMap(_.mapValue(wio.embedding.unconvertState)),
        lastSeenState.flatMap(wio.embedding.unconvertState),
        input,
      )
      visitor.run.map(x => input.map(wio.embedding.convertState(x, _)))
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result = {
      val (trigger, rest) = extractFirstInterruption(recurse(wio.interruption, lastSeenState, result = None))
        .getOrElse(throw new Exception(s"Couldn't extract interruption trigger from $wio"))
      WIOExecutionProgress.Interruptible(
        recurse(wio.base, input, result = None),
        trigger,
        rest,
        result,
      )
    }

    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result = WIOExecutionProgress.Timer(
      WIOMeta.Timer(
        wio.duration match {
          case DurationSource.Static(duration)     => duration.some
          case DurationSource.Dynamic(getDuration) => input.map(getDuration)
        },
        None,
        wio.name,
      ),
      result,
    )

    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result =
      WIOExecutionProgress.Timer(WIOMeta.Timer(None, wio.resumeAt.some, None), result)

    def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result = {
      val res = ExecutedResult(wio.output, wio.index).some
      recurse(wio.original, wio.input.some, res)
    }

    def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result = recurse(wio.original, wio.input.some, None)

    override def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Result =
      WIOExecutionProgress.Retried(recurse(wio.base, input))

    def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState]): Result = {
      WIOExecutionProgress.Parallel(wio.elements.map(elem => recurse(elem.wio, input, result = None)), result)
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result = {
      WIOExecutionProgress.Checkpoint(recurse(wio.base, input, result = None), result)
    }

    override def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result =
      WIOExecutionProgress.Recovery(result)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      val elemModel     = new ExecProgressVisitor(wio.elemWorkflow, None, None, None).run.toModel
      val subProgresses = input
        .map(wio.state)
        .getOrElse(Map.empty)
        .map { case (elemId, state) => elemId -> ExecutionProgressEvaluator.run(state, None, None) }

      WIOExecutionProgress.ForEach(result, elemModel, subProgresses, wio.meta)
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[F, I1, E1, O1, Ctx],
        input: Option[I1],
        result: WIOExecutionProgress.ExecutionResult[WCState[Ctx]] = this.result,
    ): Result = {
      val state = result.flatMap(_.value.toOption).orElse(lastSeenState)
      new ExecProgressVisitor(wio, result, state, input).run
    }

    extension (m: ErrorMeta[?]) {
      def toModel: Option[WIOMeta.Error] = m match {
        case ErrorMeta.NoError()     => None
        case ErrorMeta.Present(name) => WIOMeta.Error(name).some
      }
    }
  }

  // extractFirstInterruption logic remains same (only type signatures changed for consistency)
  def extractFirstInterruption[S](flow: WIOExecutionProgress[S]): Option[(WIOExecutionProgress.Interruption[S], Option[WIOExecutionProgress[S]])] = {
    (flow: @unchecked) match {
      case WIOExecutionProgress.Sequence(steps)                               =>
        extractFirstInterruption(steps.head).map { case (first, rest) =>
          val updatedSteps = rest match {
            case Some(value) => steps.toList.updated(0, value)
            case None        => if steps.size > 1 then steps.tail.toList else Nil
          }
          val remaining    = updatedSteps match {
            case Nil      => None
            case h :: Nil => Some(h)
            case many     => Some(WIOExecutionProgress.Sequence(many))
          }
          (first, remaining)
        }
      case x: WIOExecutionProgress.HandleSignal[S]                            => Some((x, None))
      case x: WIOExecutionProgress.Timer[S]                                   => Some((x, None))
      case WIOExecutionProgress.HandleError(base, handler, errorMeta, result) =>
        extractFirstInterruption(base).map { case (first, rest) =>
          first -> rest.map(x => WIOExecutionProgress.HandleError(x, handler, errorMeta, result))
        }
      case x: WIOExecutionProgress.Retried[S]                                 => extractFirstInterruption(x.base)
      case _                                                                  => None
    }
  }
}
