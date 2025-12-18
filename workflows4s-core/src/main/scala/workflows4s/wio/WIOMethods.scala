package workflows4s.wio

import workflows4s.wio.internal.{EventHandler, ExecutionProgressEvaluator}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.{Duration, Instant}
import scala.annotation.targetName
import scala.reflect.ClassTag

trait WIOMethods[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]] { self: WIO[F, In, Err, Out, Ctx] =>

  def flatMap[Err1 >: Err, Out1 <: WCState[Ctx]](f: Out => WIO[F, Out, Err1, Out1, Ctx])(using
      errorCt: ErrorMeta[Err1],
  ): WIO[F, In, Err1, Out1, Ctx] = WIO.FlatMap(this, f, errorCt)

  def map[Out1 <: WCState[Ctx]](f: Out => Out1): WIO[F, In, Err, Out1, Ctx] = WIO.Transform(
    this,
    identity[In],
    (_: In, out: Either[Err, Out]) => out.map(f),
  )

  def transform[NewIn, NewOut <: WCState[Ctx]](f: NewIn => In, g: (NewIn, Out) => NewOut): WIO[F, NewIn, Err, NewOut, Ctx] =
    WIO.Transform(this, f, (in: NewIn, out: Either[Err, Out]) => out.map(g(in, _)))

  def transformInput[NewIn](f: NewIn => In): WIO[F, NewIn, Err, Out, Ctx] = transform(f, (_, x) => x)
  def provideInput(value: In): WIO[F, Any, Err, Out, Ctx]                 = transformInput[Any](_ => value)

  def transformOutput[NewOut <: WCState[Ctx], In1 <: In](f: (In1, Out) => NewOut): WIO[F, In1, Err, NewOut, Ctx] = transform(identity, f)

  def handleErrorWith[Err1, Out1 >: Out <: WCState[Ctx], ErrIn >: Err](
      wio: WIO[F, (WCState[Ctx], ErrIn), Err1, Out1, Ctx],
  )(using errMeta: ErrorMeta[ErrIn], newErrMeta: ErrorMeta[Err1]): WIO[F, In, Err1, Out1, Ctx] = {
    WIO.HandleErrorWith(this, wio, errMeta, newErrMeta)
  }

  def andThen[Err1 >: Err, Out1 <: WCState[Ctx]](next: WIO[F, Out, Err1, Out1, Ctx]): WIO[F, In, Err1, Out1, Ctx] = WIO.AndThen(this, next)

  @targetName("andThenOp")
  def >>>[Err1 >: Err, Out1 <: WCState[Ctx]](next: WIO[F, Out, Err1, Out1, Ctx]): WIO[F, In, Err1, Out1, Ctx] = andThen(next)

  def interruptWith[Out1 >: Out <: WCState[Ctx], Err1 >: Err, In1 <: In](
      interruption: WIO.Interruption[F, Ctx, Err1, Out1],
  ): WIO.HandleInterruption[F, Ctx, In1, Err1, Out1] =
    WIO.HandleInterruption(this, interruption.handler, WIO.HandleInterruption.InterruptionStatus.Pending, interruption.tpe)

  /** checkpointed now uses F. Pure events are lifted into F using the Effect instance provided at the time of evaluation.
    */
  def checkpointed[Evt <: WCEvent[Ctx], In1 <: In, Out1 >: Out <: WCState[Ctx]](
      genEvent: (In1, Out1) => Evt,
      handleEvent: (In1, Evt) => Out1,
  )(using evtCt: ClassTag[Evt], E: workflows4s.runtime.instanceengine.Effect[F]): WIO[F, In1, Err, Out1, Ctx] = {
    WIO.Checkpoint(
      this,
      (a: In1, b: Out1) => E.pure(genEvent(a, b)),
      EventHandler[WCEvent[Ctx], In1, Out1, Evt](evtCt.unapply, identity, handleEvent),
    )
  }

  def toProgress: WIOExecutionProgress[WCState[Ctx]] =
    ExecutionProgressEvaluator.run[F, Ctx, In, Err, Out](this, None, None)

  def asExecuted: Option[WIO.Executed[F, Ctx, Err, Out, ?]] = this match {
    case x: WIO.Executed[F, Ctx, Err, Out, ?] => Some(x)
    case _                                    => None
  }

  type Now = Instant

  def retry(onError: (Throwable, WCState[Ctx], Now) => F[Option[Instant]]): WIO[F, In, Err, Out, Ctx] =
    WIO.Retry(this, onError)

  def retryIn(onError: PartialFunction[Throwable, Duration])(using E: workflows4s.runtime.instanceengine.Effect[F]): WIO[F, In, Err, Out, Ctx] = {
    val adapted: (Throwable, WCState[Ctx], Now) => F[Option[Instant]] = (err, _, now) => {
      E.pure(onError.lift(err).map(d => now.plus(d)))
    }
    WIO.Retry(this, adapted)
  }

}
