package workflow4s.wio.model

import cats.syntax.all.*
import workflow4s.wio.{Visitor, WCState, WIO, WorkflowContext}
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
      val signalName = ModelUtils.getPrettyNameForClass(wio.sigDef.reqCt)
      WIOModel.HandleSignal(signalName, wio.errorCt, m.name, m.description) // TODO error
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = {
      WIOModel.RunIO(wio.errorCt, m.name, m.description)
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = {
      WIOModel.Sequence(Seq(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.errorCt)))
    }
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                           = recurse(wio.base)
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                             = WIOModel.Noop
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             =
      new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description)).run
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      WIOModel.HandleError(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.newErrorMeta), wio.handledErrorMeta)
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      WIOModel.HandleError(recurse(wio.base, None), recurse(wio.handleError, None), wio.handledErrorMeta)
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      (recurse(wio.first, None), recurse(wio.second, None)) match {
        case (WIOModel.Sequence(steps1), WIOModel.Sequence(steps2)) => WIOModel.Sequence(steps1 ++ steps2)
        case (x, WIOModel.Sequence(steps2))                         => WIOModel.Sequence(List(x) ++ steps2)
        case (WIOModel.Sequence(steps1), x)                         => WIOModel.Sequence(steps1 ++ List(x))
        case (a, b)                                                 => WIOModel.Sequence(List(a, b))
      }
    }

    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                   = WIOModel.Pure(m.name, m.description, wio.errorMeta)
    def onDoWhile[Out1 <: WCState[Ctx]](wio: WIO.DoWhile[Ctx, In, Err, Out1, Out]): Result =
      WIOModel.Loop(recurse(wio.loop), None)
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                   =
      WIOModel.Fork(wio.branches.map(x => WIOModel.Branch(recurse(x.wio), None)))
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      recurse(wio.inner)
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result       = {
      val trigger = {
        // we are abusing the interpreter pattern a bit to get more concrete result
        new ModelVisitor(wio.interruption.trigger, Metadata.empty).onSignal(wio.interruption.trigger)
      }
      WIOModel.Interruptible(
        recurse(wio.base),
        trigger,
        stripFirst(recurse(wio.interruption.finalWIO), trigger),
      )
    }

    def recurse[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](wio: WIO[I1, E1, O1, C], meta: Option[Metadata] = Some(m)): WIOModel = {
      new ModelVisitor(wio, meta.getOrElse(Metadata.empty)).run
    }

  }

  def stripFirst(flow: WIOModel, toBeStrpped: WIOModel): Option[WIOModel] = {
    def notFirst(x: WIOModel)                    = throw new Exception(s"Requested element is not the first in sequence. Required: ${toBeStrpped}, found as first: ${x}")
    def handleRaw(x: WIOModel): Option[WIOModel] = if (x == toBeStrpped) None else notFirst(x)
    if (flow == toBeStrpped) None
    else {
      flow match {
        case WIOModel.Sequence(steps)                                        =>
          stripFirst(steps.head, toBeStrpped) match {
            case Some(value) => WIOModel.Sequence(steps.toList.updated(0, value)).some
            case None        =>
              if (steps.size > 2) WIOModel.Sequence(steps.drop(1)).some
              else steps(1).some
          }
        case x @ WIOModel.Dynamic(name, error)                               => handleRaw(x)
        case x @ WIOModel.RunIO(error, name, description)                    => handleRaw(x)
        case x @ WIOModel.HandleSignal(signalName, error, name, description) => handleRaw(x)
        case WIOModel.HandleError(base, handler, errorName)                  => stripFirst(base, toBeStrpped).map(WIOModel.HandleError(_, handler, errorName))
        case x @ WIOModel.Noop                                               => handleRaw(x) // does it make any sense?, can noop be element in sequence?
        case x @ WIOModel.Pure(name, description, errorMeta)                 => handleRaw(x)
        case WIOModel.Loop(base, conditionLabel)                             => stripFirst(base, toBeStrpped).map(WIOModel.Loop(_, conditionLabel))
        case x @ WIOModel.Fork(branches)                                     => handleRaw(x)
        case WIOModel.Interruptible(base, trigger, flow)                     => stripFirst(base, toBeStrpped).map(WIOModel.Interruptible(_, trigger, flow))
      }
    }
  }

}
