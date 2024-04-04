package workflow4s.wio.internal

import cats.effect.IO
import workflow4s.wio.Interpreter.SignalResponse
import workflow4s.wio.{Interpreter, SignalDef, VisitorModule, WIOT, WorkflowContext}

class SignalEvaluator[Ctx <: WorkflowContext](val c: Ctx, interpreter: Interpreter[Ctx]) extends VisitorModule[Ctx] {
  import c.WIO
  import NextWfState.{NewBehaviour, NewValue}

  def handleSignal[Req, Resp, In, Out](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: Ctx#WIO[In, Nothing, Out],
      state: In,
  ): SignalResponse[Ctx, Resp] = {
    val visitor = new SignalVisitor(wio, signalDef, req, state)
    visitor.run
      .map(wfIO => wfIO.map({ case (wf, resp) => wf.toActiveWorkflow(interpreter) -> resp }))
      .map(SignalResponse.Ok(_))
      .getOrElse(SignalResponse.UnexpectedSignal())
  }

  private class SignalVisitor[Resp, Err, Out, In, Req](
      wio: WIO[In, Err, Out],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      state: In,
  ) extends Visitor[In, Err, Out](wio) {
    type NewWf           = NextWfState[Err, Out]
    override type Result = Option[IO[(NewWf, Resp)]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result           = {
      wio.sigHandler
        .run(signalDef)(req, state)
        .map(ioOpt =>
          for {
            evt   <- ioOpt
            _     <- interpreter.journal.save(wio.evtHandler.convert(evt))
            result = wio.evtHandler.handle(state, evt)
          } yield NewValue(result._1) -> signalDef.respCt.unapply(result._2).get, // TODO .get is unsafe
        )
    }
    def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result                                         = None
    def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result                = {
      recurse(wio.base, state).map(_.map({ case (wf, resp) => preserveFlatMap(wio, wf) -> resp }))
    }
    def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result                                 = {
      recurse(wio.base, wio.contramapInput(state)).map(_.map({ case (wf, resp) => preserveMap(wio, wf, state) -> resp }))
    }
    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result = {
      recurse(wio.inner, state).map(_.map({ case (wf, resp) => preserveHandleQuery(wio, wf) -> resp }))
    }
    def onNoop(wio: WIO.Noop): Result                                                                   = None
    def onNamed(wio: WIO.Named[In, Err, Out]): Result                                                   = recurse(wio.base, state)
    def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result                         =
      recurse(wio.base, state).map(_.map({ case (wf, resp) =>
        val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } = wf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }]
        applyHandleError(wio, casted, state) -> resp
      }))
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result                 =
      recurse(wio.base, state).map(_.map({ case (wf, resp) =>
        val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
          wf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state) -> resp
      }))
    def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result                                   = {
      recurse(wio.first, state).map(_.map({ case (wf, resp) => preserveAndThen(wio, wf) -> resp }))
    }
    def onPure(wio: WIO.Pure[In, Err, Out]): Result                                                     = None
    def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result                                   =
      recurse(wio.current, state).map(_.map({ case (wf, resp) => applyOnDoWhile(wio, wf) -> resp }))
    def onFork(wio: WIO.Fork[In, Err, Out]): Result                                                     = ??? // TODO, proper error handling, should never happen

    def recurse[I1, E1, O1](wio: WIO[I1, E1, O1], s: I1): SignalVisitor[Resp, E1, O1, I1, Req]#Result =
      new SignalVisitor(wio, signalDef, req, s).run

  }
}
