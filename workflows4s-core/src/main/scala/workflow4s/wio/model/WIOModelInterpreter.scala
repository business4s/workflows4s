package workflow4s.wio.model

import cats.syntax.all.*
import workflow4s.wio.WIO.Timer.DurationSource
import workflow4s.wio.{ErrorMeta, Visitor, WCState, WIO, WorkflowContext}
object WIOModelInterpreter {

  def run(wio: WIO[?, ?, ?, ?]): WIOModel = {
    new ModelVisitor(wio, Metadata.empty).run
  }

  case class Metadata(name: Option[String], description: Option[String])

  object Metadata {
    val empty = Metadata(None, None)
  }

  private class ModelVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](wio: WIO[In, Err, Out, Ctx], m: Metadata)
      extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = WIOModel

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): WIOModel.HandleSignal      = {
      WIOModel.HandleSignal(wio.meta.signalName, wio.meta.error.toModel, wio.meta.operationName)
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = {
      WIOModel.RunIO(wio.meta.error.toModel, wio.meta.name)
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = {
      WIOModel.Sequence(Seq(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.errorMeta.toModel)))
    }
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                           = recurse(wio.base)
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                             = WIOModel.Noop
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             =
      new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description)).run
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      WIOModel.HandleError(
        recurse(wio.base, None),
        WIOModel.Dynamic(m.name, wio.newErrorMeta.toModel),
        wio.handledErrorMeta.toModel,
      )
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      WIOModel.HandleError(
        recurse(wio.base, None),
        recurse(wio.handleError, None),
        // handling Nothing makes no sense but for the purpose of drafts we have to allow for it
        // unless we bank it on type-level
        wio.handledErrorMeta.toModel,
      )
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      (recurse(wio.first, None), recurse(wio.second, None)) match {
        case (WIOModel.Sequence(steps1), WIOModel.Sequence(steps2)) => WIOModel.Sequence(steps1 ++ steps2)
        case (x, WIOModel.Sequence(steps2))                         => WIOModel.Sequence(List(x) ++ steps2)
        case (WIOModel.Sequence(steps1), x)                         => WIOModel.Sequence(steps1 ++ List(x))
        case (a, b)                                                 => WIOModel.Sequence(List(a, b))
      }
    }

    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                             = WIOModel.Pure(m.name, wio.errorMeta.toModel)
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result =
      WIOModel.Loop(recurse(wio.loop), wio.meta.conditionName, wio.meta.releaseBranchName, wio.meta.restartBranchName, wio.onRestart.map(recurse(_)))
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                             =
      WIOModel.Fork(wio.branches.map(x => WIOModel.Branch(recurse(x.wio), x.name)), wio.name)
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      recurse(wio.inner)
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      val trigger: WIOModel & WIOModel.Interruption = {
        // we are abusing the interpreter pattern a bit to get more concrete result
        wio.interruption.trigger match {
          case x @ WIO.HandleSignal(_, _, _, _) =>
            new ModelVisitor(x, Metadata.empty).onSignal(x)
          case x @ WIO.Timer(_, _, _, _)     =>
            new ModelVisitor(x, Metadata.empty).onTimer(x)
          case x @ WIO.AwaitingTime(_, _)    =>
            new ModelVisitor(x, Metadata.empty).onAwaitingTime(x)
        }
      }
      WIOModel.Interruptible(
        recurse(wio.base),
        trigger,
        stripFirst(recurse(wio.interruption.finalWIO), trigger),
      )
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): WIOModel.Timer = WIOModel.Timer(
      wio.duration match {
        case DurationSource.Static(duration)     => duration.some
        case DurationSource.Dynamic(getDuration) => none
      },
      wio.name,
    )

    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Nothing =
      ??? // TODO, shouldnt happen unitl we start capturing model of inflight workflows

    def recurse[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](wio: WIO[I1, E1, O1, C], meta: Option[Metadata] = Some(m)): WIOModel = {
      new ModelVisitor(wio, meta.getOrElse(Metadata.empty)).run
    }

    implicit class ErrorMetaOps(m: ErrorMeta[?]) {
      def toModel: Option[WIOModel.Error] = m match {
        case ErrorMeta.NoError()     => None
        case ErrorMeta.Present(name) => WIOModel.Error(name).some
      }
    }

  }

  def stripFirst(flow: WIOModel, toBeStripped: WIOModel): Option[WIOModel] = {
    def notFirst(x: WIOModel)                    = throw new Exception(
      s"Requested element is not the first in sequence. Required: ${toBeStripped}, found as first: ${x}",
    )
    def handleRaw(x: WIOModel): Option[WIOModel] = if (x == toBeStripped) None else notFirst(x)
    if (flow == toBeStripped) None
    else {
      flow match {
        case WIOModel.Sequence(steps)                           =>
          stripFirst(steps.head, toBeStripped) match {
            case Some(value) => WIOModel.Sequence(steps.toList.updated(0, value)).some
            case None        =>
              if (steps.size > 2) WIOModel.Sequence(steps.drop(1)).some
              else steps(1).some
          }
        case x @ WIOModel.Dynamic(_, _)                         => handleRaw(x)
        case x @ WIOModel.RunIO(error, name)                    => handleRaw(x)
        case x @ WIOModel.HandleSignal(signalName, error, name) => handleRaw(x)
        case WIOModel.HandleError(base, handler, errorName)     => stripFirst(base, toBeStripped).map(WIOModel.HandleError(_, handler, errorName))
        case x @ WIOModel.Noop                                  => handleRaw(x) // does it make any sense?, can noop be element in sequence?
        case x @ WIOModel.Pure(name, errorMeta)                 => handleRaw(x)
        case x: WIOModel.Loop                                   => stripFirst(x.base, toBeStripped).map(y => x.copy(base = y))
        case x @ WIOModel.Fork(_, _)                            => handleRaw(x)
        case x @ WIOModel.Timer(_, _)                           => handleRaw(x)
        case WIOModel.Interruptible(base, trigger, flow)        => stripFirst(base, toBeStripped).map(WIOModel.Interruptible(_, trigger, flow))
      }
    }
  }

}
