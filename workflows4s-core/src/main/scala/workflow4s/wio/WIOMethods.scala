package workflow4s.wio

import scala.annotation.targetName

import workflow4s.wio.model.{ModelUtils, WIOModel, WIOModelInterpreter}

trait WIOMethods[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]] { self: WIO[In, Err, Out, Ctx] =>
  def flatMap[Err1 >: Err, Out1 <: WCState[Ctx]](f: Out => WIO[Out, Err1, Out1, Ctx])(using
      errorCt: ErrorMeta[Err1],
  ): WIO[In, Err1, Out1, Ctx] = WIO.FlatMap(this, f, errorCt)

  def map[Out1 <: WCState[Ctx]](f: Out => Out1): WIO[In, Err, Out1, Ctx] = WIO.Map(
    this,
    identity[In],
    (_: In, out: Out) => f(out),
  )

//    def checkpointed[Evt, O1, StIn1 <: StIn, StOut1 >: StOut](genEvent: (StOut, Out) => Evt)(
//        handleEvent: (StIn1, Evt) => (StOut1, O1),
//    ): WIO[Err, O1, StIn, StOut] = ???

  def transform[NewIn, NewOut <: WCState[Ctx]](f: NewIn => In, g: (NewIn, Out) => NewOut): WIO[NewIn, Err, NewOut, Ctx] =
    WIO.Map(this, f, (in: NewIn, out: Out) => g(in, out))

  def transformInput[NewIn](f: NewIn => In): WIO[NewIn, Err, Out, Ctx] = transform(f, (_, x) => x)
  def provideInput(value: In): WIO[Any, Err, Out, Ctx]                 = transformInput[Any](_ => value)

  // TODO isnt that just map?
  def transformOutput[NewOut <: WCState[Ctx], In1 <: In](f: (In1, Out) => NewOut): WIO[In1, Err, NewOut, Ctx] = transform(identity, f)

  //  def handleError[Err1, StIn1 <: StIn, Out1 >: Out, StOut1 >: StOut, ErrIn >: Err](
  //      f: ErrIn => WIO[Err1, Out1, StIn1, StOut1],
  //  )(using errCt: ClassTag[ErrIn], newErrCt: ClassTag[Err1]): WIO[Err1, Out1, StIn1, StOut1] =
  //    WIO.HandleError(this, f, errCt, newErrCt)

  def handleErrorWith[Err1, Out1 >: Out <: WCState[Ctx], ErrIn >: Err](
      wio: WIO[(WCState[Ctx], ErrIn), Err1, Out1, Ctx],
  )(using errMeta: ErrorMeta[ErrIn], newErrMeta: ErrorMeta[Err1]): WIO[In, Err1, Out1, Ctx] = {
    WIO.HandleErrorWith(this, wio, errMeta, newErrMeta)
  }

  def named(name: String, description: Option[String] = None): WIO[In, Err, Out, Ctx] = {
    WIO.Named(this, name, description, ErrorMeta.noError)
  }

  def autoNamed[Err1 >: Err](
      description: Option[String] = None,
  )(using name: sourcecode.Name, errorCt: ErrorMeta[Err1]): WIO[In, Err, Out, Ctx] = {
    val polishedName = ModelUtils.prettifyName(name.value)
    WIO.Named(this, polishedName, description, errorCt)
  }

  def andThen[Err1 >: Err, Out1 <: WCState[Ctx]](next: WIO[Out, Err1, Out1, Ctx]): WIO[In, Err1, Out1, Ctx] = WIO.AndThen(this, next)

  @targetName("andThenOp")
  def >>>[Err1 >: Err, Out1 <: WCState[Ctx]](next: WIO[Out, Err1, Out1, Ctx]): WIO[In, Err1, Out1, Ctx] = andThen(next)

  def interruptWith[Out1 >: Out <: WCState[Ctx], Err1 >: Err, In1 <: In](
      interruption: WIO.Interruption[Ctx, Err1, Out1, ?, ?],
  ): WIO.HandleInterruption[Ctx, In1, Err1, Out1] =
    WIO.HandleInterruption(this, interruption)

  def getModel: WIOModel = WIOModelInterpreter.run(this)

}
