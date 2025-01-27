package workflows4s.wio.model

import cats.syntax.all.*
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.{ErrorMeta, Visitor, WCState, WIO, WorkflowContext}
object WIOModelInterpreter {

  def run(wio: WIO[?, ?, ?, ?]): WIOModel = {
    new ModelVisitor(wio, Metadata.empty, false).run
  }

  case class Metadata(name: Option[String], description: Option[String])

  object Metadata {
    val empty = Metadata(None, None)
  }

  private class ModelVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      m: Metadata,
      isExecuted: Boolean,
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = WIOModel

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): WIOModel.HandleSignal      = {
      WIOModel.HandleSignal(wio.meta.signalName, wio.meta.error.toModel, wio.meta.operationName, isExecuted)
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = {
      WIOModel.RunIO(wio.meta.error.toModel, wio.meta.name, isExecuted)
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = {
      WIOModel.Sequence(Seq(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.errorMeta.toModel, isExecuted)), isExecuted)
    }
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          = recurse(wio.base)
    def onNoop(wio: WIO.End[Ctx]): Result                                                                              = WIOModel.End(isExecuted)
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             =
      new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description), isExecuted).run
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      WIOModel.HandleError(
        recurse(wio.base, None),
        WIOModel.Dynamic(m.name, wio.newErrorMeta.toModel, isExecuted),
        wio.handledErrorMeta.toModel,
        isExecuted,
      )
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      WIOModel.HandleError(
        recurse(wio.base, None),
        recurse(wio.handleError, None),
        // handling Nothing makes no sense but for the purpose of drafts we have to allow for it
        // unless we bank it on type-level
        wio.handledErrorMeta.toModel,
        isExecuted,
      )
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      (recurse(wio.first, None), recurse(wio.second, None)) match {
        case (WIOModel.Sequence(steps1, _), WIOModel.Sequence(steps2, _)) => WIOModel.Sequence(steps1 ++ steps2, isExecuted)
        case (x, WIOModel.Sequence(steps2, _))                            => WIOModel.Sequence(List(x) ++ steps2, isExecuted)
        case (WIOModel.Sequence(steps1, _), x)                            => WIOModel.Sequence(steps1 ++ List(x), isExecuted)
        case (a, b)                                                       => WIOModel.Sequence(List(a, b), isExecuted)
      }
    }

    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                             = WIOModel.Pure(wio.meta.name, wio.meta.error.toModel, isExecuted)
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result =
      WIOModel.Loop(
        recurse(wio.loop),
        wio.meta.conditionName,
        wio.meta.releaseBranchName,
        wio.meta.restartBranchName,
        wio.onRestart.map(recurse(_)),
        isExecuted, // this is wrong to an extend
      )
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                             =
      WIOModel.Fork(wio.branches.zipWithIndex.map((x, idx) => WIOModel.Branch(recurse(x.wio), x.name)), wio.name, isExecuted)
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      recurse(wio.inner) // TODO should express in model?
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      // TODO better error handling. meaningful exception would be a good start
      val Some((trigger, rest)) = extractFirstInterruption(recurse(wio.interruption))
      WIOModel.Interruptible(
        recurse(wio.base),
        trigger,
        rest,
        isExecuted,
      )
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): WIOModel.Timer = WIOModel.Timer(
      wio.duration match {
        case DurationSource.Static(duration)     => duration.some
        case DurationSource.Dynamic(getDuration) => none
      },
      wio.name,
      isExecuted,
    )

    // TODO, shouldnt happen unitl we start capturing model of inflight workflows
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]) = WIOModel.Timer(None, None, isExecuted) // TODO persist duration and name
    def onExecuted(wio: WIO.Executed[Ctx, Err, Out]): Result     = recurse(wio.original, isExecuted = true)
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result     = recurse(wio.original, isExecuted = false)

    def recurse[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](
        wio: WIO[I1, E1, O1, C],
        meta: Option[Metadata] = Some(m),
        isExecuted: Boolean = this.isExecuted,
    ): WIOModel = {
      new ModelVisitor(wio, meta.getOrElse(Metadata.empty), isExecuted).run
    }

    extension (m: ErrorMeta[?]) {
      def toModel: Option[WIOModel.Error] = m match {
        case ErrorMeta.NoError()     => None
        case ErrorMeta.Present(name) => WIOModel.Error(name).some
      }
    }

  }

  def extractFirstInterruption(flow: WIOModel): Option[(WIOModel.Interruption, Option[WIOModel])] = {
    flow match {
      case WIOModel.Sequence(steps, isExecuted)                       =>
        extractFirstInterruption(steps.head).map((first, rest) => (first, rest.map(x => WIOModel.Sequence(steps.toList.updated(0, x), isExecuted))))
      case WIOModel.Dynamic(_, _, _)                                  => None
      case WIOModel.RunIO(_, _, _)                                    => None
      case x @ WIOModel.HandleSignal(_, _, _, _)                      => Some((x, None))
      case WIOModel.HandleError(base, handler, errorName, isExecuted) =>
        extractFirstInterruption(base).map((first, rest) => first -> rest.map(x => WIOModel.HandleError(x, handler, errorName, isExecuted)))
      case x @ WIOModel.End(_)                                        => None
      case x @ WIOModel.Pure(_, _, _)                                 => None
      case x: WIOModel.Loop                                           => None
      case x @ WIOModel.Fork(_, _, _)                                 => None
      case x @ WIOModel.Timer(_, _, _)                                => (x, None).some
      case WIOModel.Interruptible(base, trigger, flow, _)             => None
    }
  }

}
