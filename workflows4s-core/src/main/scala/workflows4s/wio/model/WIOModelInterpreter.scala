package workflows4s.wio.model

import cats.syntax.all.*
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.{ErrorMeta, Visitor, WCState, WIO, WorkflowContext}
object WIOModelInterpreter {

  def run(wio: WIO[?, ?, ?, ?]): WIOModel = {
    new ModelVisitor(wio, Metadata.empty, WIOId.root, false).run
  }

  case class Metadata(name: Option[String], description: Option[String])

  object Metadata {
    val empty = Metadata(None, None)
  }

  private class ModelVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      m: Metadata,
      id: WIOId,
      isExecuted: Boolean,
  ) extends Visitor[Ctx, In, Err, Out](wio, id) {
    override type Result = WIOModel

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): WIOModel.HandleSignal      = {
      WIOModel.HandleSignal(id, wio.meta.signalName, wio.meta.error.toModel, wio.meta.operationName, isExecuted)
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = {
      WIOModel.RunIO(id, wio.meta.error.toModel, wio.meta.name, isExecuted)
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = {
      WIOModel.Sequence(id, Seq(recurse(wio.base, 0, None), WIOModel.Dynamic(id.child(1), m.name, wio.errorMeta.toModel, isExecuted)), isExecuted)
    }
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          = recurse(wio.base, 0)
    def onNoop(wio: WIO.End[Ctx]): Result                                                                              = WIOModel.End(id, isExecuted)
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             =
      new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description), id.child(0), isExecuted).run
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      WIOModel.HandleError(
        id,
        recurse(wio.base, 0, None),
        WIOModel.Dynamic(id.child(1), m.name, wio.newErrorMeta.toModel, isExecuted),
        wio.handledErrorMeta.toModel,
        isExecuted,
      )
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      WIOModel.HandleError(
        id,
        recurse(wio.base, 0, None),
        recurse(wio.handleError, 1, None),
        // handling Nothing makes no sense but for the purpose of drafts we have to allow for it
        // unless we bank it on type-level
        wio.handledErrorMeta.toModel,
        isExecuted,
      )
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      (recurse(wio.first, 0, None), recurse(wio.second, 1, None)) match {
        case (WIOModel.Sequence(_, steps1, _), WIOModel.Sequence(_, steps2, _)) => WIOModel.Sequence(id, steps1 ++ steps2, isExecuted)
        case (x, WIOModel.Sequence(_, steps2, _))                               => WIOModel.Sequence(id, List(x) ++ steps2, isExecuted)
        case (WIOModel.Sequence(_, steps1, _), x)                               => WIOModel.Sequence(id, steps1 ++ List(x), isExecuted)
        case (a, b)                                                             => WIOModel.Sequence(id, List(a, b), isExecuted)
      }
    }

    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                             = WIOModel.Pure(id, wio.meta.name, wio.meta.error.toModel, isExecuted)
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result =
      WIOModel.Loop(
        id,
        recurse(wio.loop, 0),
        wio.meta.conditionName,
        wio.meta.releaseBranchName,
        wio.meta.restartBranchName,
        wio.onRestart.map(recurse(_, 1)),
        isExecuted, // this is wrong to an extend
      )
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                             =
      WIOModel.Fork(id, wio.branches.zipWithIndex.map((x, idx) => WIOModel.Branch(recurse(x.wio, idx), x.name)), wio.name, isExecuted)
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      recurse(wio.inner, 0) // TODO should express in model?
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      // TODO better error handling. meaningful exception would be a good start
      val Some((trigger, rest)) = extractFirstInterruption(recurse(wio.interruption, 1))
      WIOModel.Interruptible(
        id,
        recurse(wio.base, 0),
        trigger,
        rest,
        isExecuted,
      )
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): WIOModel.Timer = WIOModel.Timer(
      id,
      wio.duration match {
        case DurationSource.Static(duration)     => duration.some
        case DurationSource.Dynamic(getDuration) => none
      },
      wio.name,
      isExecuted,
    )

    // TODO, shouldnt happen unitl we start capturing model of inflight workflows
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]) = WIOModel.Timer(id, None, None, isExecuted) // TODO persist duration and name
    def onExecuted(wio: WIO.Executed[Ctx, Err, Out]): Result     = recurse(wio.original, 0, isExecuted = true)
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result     = recurse(wio.original, 0, isExecuted = false)

    def recurse[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](
        wio: WIO[I1, E1, O1, C],
        idx: Int,
        meta: Option[Metadata] = Some(m),
        isExecuted: Boolean = this.isExecuted,
    ): WIOModel = {
      new ModelVisitor(wio, meta.getOrElse(Metadata.empty), id.child(idx), isExecuted).run
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
      case WIOModel.Sequence(id, steps, isExecuted)                       =>
        extractFirstInterruption(steps.head).map((first, rest) =>
          (first, rest.map(x => WIOModel.Sequence(id, steps.toList.updated(0, x), isExecuted))),
        )
      case WIOModel.Dynamic(_, _, _, _)                                   => None
      case WIOModel.RunIO(_, _, _, _)                                     => None
      case x @ WIOModel.HandleSignal(_, _, _, _, _)                       => Some((x, None))
      case WIOModel.HandleError(id, base, handler, errorName, isExecuted) =>
        extractFirstInterruption(base).map((first, rest) => first -> rest.map(x => WIOModel.HandleError(id, x, handler, errorName, isExecuted)))
      case x @ WIOModel.End(_, _)                                         => None
      case x @ WIOModel.Pure(_, _, _, _)                                  => None
      case x: WIOModel.Loop                                               => None
      case x @ WIOModel.Fork(_, _, _, _)                                  => None
      case x @ WIOModel.Timer(_, _, _, _)                                 => (x, None).some
      case WIOModel.Interruptible(id, base, trigger, flow, _)             => None
    }
  }

}
