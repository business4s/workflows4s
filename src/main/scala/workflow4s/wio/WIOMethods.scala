package workflow4s.wio

import workflow4s.wio.model.ModelUtils

import scala.annotation.targetName

trait WIOMethodsParent { self: WorkflowContext =>

  trait WIOMethods[+Err, +Out, -StIn, +StOut] { self: WIO[Err, Out, StIn, StOut] =>
    def flatMap[Err1 >: Err, StOut1, Out1](f: Out => WIO[Err1, Out1, StOut, StOut1])(implicit
        errorCt: ErrorMeta[Err1],
    ): WIO[Err1, Out1, StIn, StOut1] = {
      WIO.FlatMap(this, f, errorCt)
    }

    def map[Out1](f: Out => Out1): WIO[Err, Out1, StIn, StOut] = WIO.Map(
      this,
      identity[StIn],
      (sIn: StIn, sOut: StOut, out: Out) => (sOut, f(out)),
    )

    // TODO, variance can be fooled if moved to extension method
    def checkpointed[Evt, O1, StIn1 <: StIn, StOut1 >: StOut](genEvent: (StOut, Out) => Evt)(
        handleEvent: (StIn1, Evt) => (StOut1, O1),
    ): WIO[Err, O1, StIn, StOut] = ???

    def transformState[NewStIn, NewStOut](
        f: NewStIn => StIn,
        g: (NewStIn, StOut) => NewStOut,
    ): WIO[Err, Out, NewStIn, NewStOut] = WIO.Map[Err, Out, Out, StIn, NewStIn, StOut, NewStOut](
      this,
      f,
      (sIn: NewStIn, sOut: StOut, o: Out) => g(sIn, sOut) -> o,
    )

    def transformInputState[NewStIn](
        f: NewStIn => StIn,
    ): WIO[Err, Out, NewStIn, StOut] = transformState(f, (_, x) => x)

    def transformOutputState[NewStOut, StIn1 <: StIn](
        f: (StIn1, StOut) => NewStOut,
    ): WIO[Err, Out, StIn1, NewStOut] = transformState(identity, f)

    //  def handleError[Err1, StIn1 <: StIn, Out1 >: Out, StOut1 >: StOut, ErrIn >: Err](
    //      f: ErrIn => WIO[Err1, Out1, StIn1, StOut1],
    //  )(implicit errCt: ClassTag[ErrIn], newErrCt: ClassTag[Err1]): WIO[Err1, Out1, StIn1, StOut1] =
    //    WIO.HandleError(this, f, errCt, newErrCt)

    def handleErrorWith[Err1, Out1 >: Out, StOut1 >: StOut, ErrIn >: Err, StIn0 <: StIn, StIn1 >: StIn0](
        wio: WIO[Err1, Out1, (StIn1, ErrIn), StOut1],
    )(implicit errCt: ErrorMeta[ErrIn], newErrCt: ErrorMeta[Err1]): WIO[Err1, Out1, StIn0, StOut1] = {
      WIO.HandleErrorWith[Err1, Out1, StIn0, StOut1, Err, StIn1, Out1](this, wio, errCt, newErrCt)
    }

    def named(name: String, description: Option[String] = None): WIO[Err, Out, StIn, StOut] = {
      WIO.Named(this, name, description, ErrorMeta.noError)
    }

    def autoNamed[Err1 >: Err](
        description: Option[String] = None,
    )(implicit name: sourcecode.Name, errorCt: ErrorMeta[Err1]): WIO[Err, Out, StIn, StOut] = {
      val polishedName = ModelUtils.prettifyName(name.value)
      WIO.Named(this, polishedName, description, errorCt)
    }

    def andThen[Err1 >: Err, StOut1, Out1](next: WIO[Err1, Out1, StOut, StOut1]): WIO[Err1, Out1, StIn, StOut1] = WIO.AndThen(this, next)
    @targetName("andThenOp")
    def >>>[Err1 >: Err, StOut1, Out1](next: WIO[Err1, Out1, StOut, StOut1]): WIO[Err1, Out1, StIn, StOut1]     = andThen(next)

  }
}
