package workflows4s.wio

import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId}
import workflows4s.wio.internal.{EventHandler, ExecutionProgressEvaluator}
import workflows4s.wio.model.WIOExecutionProgress

import scala.annotation.targetName
import scala.reflect.ClassTag

trait WIOMethods[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]] { self: WIO[In, Err, Out, Ctx] =>
  def flatMap[Err1 >: Err, Out1 <: WCState[Ctx]](f: Out => WIO[Out, Err1, Out1, Ctx])(using
      errorCt: ErrorMeta[Err1],
  ): WIO[In, Err1, Out1, Ctx] = WIO.FlatMap(this, f, errorCt)

  def map[Out1 <: WCState[Ctx]](f: Out => Out1): WIO[In, Err, Out1, Ctx] = WIO.Transform(
    this,
    identity[In],
    (_: In, out: Either[Err, Out]) => out.map(f),
  )

  def transform[NewIn, NewOut <: WCState[Ctx]](f: NewIn => In, g: (NewIn, Out) => NewOut): WIO[NewIn, Err, NewOut, Ctx] =
    WIO.Transform(this, f, (in: NewIn, out: Either[Err, Out]) => out.map(g(in, _)))

  def transformInput[NewIn](f: NewIn => In): WIO[NewIn, Err, Out, Ctx] = transform(f, (_, x) => x)
  def provideInput(value: In): WIO[Any, Err, Out, Ctx]                 = transformInput[Any](_ => value)

  def transformOutput[NewOut <: WCState[Ctx], In1 <: In](f: (In1, Out) => NewOut): WIO[In1, Err, NewOut, Ctx] = transform(identity, f)

  def handleErrorWith[Err1, Out1 >: Out <: WCState[Ctx], ErrIn >: Err](
      wio: WIO[(WCState[Ctx], ErrIn), Err1, Out1, Ctx],
  )(using errMeta: ErrorMeta[ErrIn], newErrMeta: ErrorMeta[Err1]): WIO[In, Err1, Out1, Ctx] = {
    WIO.HandleErrorWith(this, wio, errMeta, newErrMeta)
  }

  def andThen[Err1 >: Err, Out1 <: WCState[Ctx]](next: WIO[Out, Err1, Out1, Ctx]): WIO[In, Err1, Out1, Ctx] = WIO.AndThen(this, next)

  @targetName("andThenOp")
  def >>>[Err1 >: Err, Out1 <: WCState[Ctx]](next: WIO[Out, Err1, Out1, Ctx]): WIO[In, Err1, Out1, Ctx] = andThen(next)

  def interruptWith[Out1 >: Out <: WCState[Ctx], Err1 >: Err, In1 <: In](
      interruption: WIO.Interruption[Ctx, Err1, Out1],
  ): WIO.HandleInterruption[Ctx, In1, Err1, Out1] =
    WIO.HandleInterruption(this, interruption.handler, WIO.HandleInterruption.InterruptionStatus.Pending, interruption.tpe)

  def checkpointed[Evt <: WCEvent[Ctx], In1 <: In, Out1 >: Out <: WCState[Ctx]](
      genEvent: (In1, Out1) => Evt,
      handleEvent: (In1, Evt) => Out1,
  )(using evtCt: ClassTag[Evt]): WIO[In1, Err, Out1, Ctx] = {
    WIO.Checkpoint(
      this,
      (a: In1, b: Out1) => genEvent(a, b).pure[IO],
      EventHandler[WCEvent[Ctx], In1, Out1, Evt](evtCt.unapply, identity, handleEvent),
    )
  }

  def toProgress: WIOExecutionProgress[WCState[Ctx]] = ExecutionProgressEvaluator.run(this, None, None)

  def asExecuted: Option[WIO.Executed[Ctx, Err, Out, ?]] = this match {
    case x: WIO.Executed[Ctx, Err, Out, ?] => x.some
    case _                                 => None
  }

}
