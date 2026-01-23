package workflows4s.wio.internal

import cats.implicits.toFoldableOps
import com.typesafe.scalalogging.StrictLogging
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus

object SignalEvaluator {

  def handleSignal[Ctx <: WorkflowContext, Req, Resp, In <: WCState[Ctx], Out <: WCState[Ctx]](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[In, Nothing, Out, Ctx],
      state: In,
  ): SignalResult[WCEvent[Ctx], Resp] = {
    val visitor = new SignalVisitor(wio, signalDef, req, state, state)
    visitor.run match {
      case Some(signalMatch) => signalMatch.produceResult(signalDef)
      case None              => SignalResult.UnexpectedSignal
    }
  }

  // Internal types for signal matching
  sealed private trait SignalMatch[Ctx <: WorkflowContext] {
    def produceResult[Resp](signalDef: SignalDef[?, Resp]): SignalResult[WCEvent[Ctx], Resp]
  }

  private object SignalMatch {
    case class Fresh[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx], Req, Resp, Evt](
        node: WIO.HandleSignal[Ctx, In, Out, Err, Req, Resp, Evt],
        input: In,
        request: Req,
    ) extends SignalMatch[Ctx] {
      def toRedelivered(storedEvent: Evt): Redelivered[Ctx, In, Err, Out, Req, Resp, Evt] =
        Redelivered(node, input, storedEvent, request)

      def produceResult[R](signalDef: SignalDef[?, R]): SignalResult[WCEvent[Ctx], R] = {
        val eventIO = node.sigHandler.handle(input, request)
        SignalResult.Processed(eventIO.map { evt =>
          val event    = node.evtHandler.convert(evt)
          val response = node.responseProducer(input, evt, request)
          SignalResult.ProcessingResult(event, extractTypedResponse(signalDef, response))
        })
      }
    }

    case class Redelivered[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx], Req, Resp, Evt](
        originalNode: WIO.HandleSignal[Ctx, In, Out, Err, Req, Resp, Evt],
        input: In,
        storedEvent: Evt,
        request: Req,
    ) extends SignalMatch[Ctx] {
      def produceResult[R](signalDef: SignalDef[?, R]): SignalResult[WCEvent[Ctx], R] = {
        val response = originalNode.responseProducer(input, storedEvent, request)
        SignalResult.Redelivered(extractTypedResponse(signalDef, response))
      }
    }

    // Wrapper for matches in embedded/inner contexts that need event conversion
    case class Embedded[OuterCtx <: WorkflowContext, InnerCtx <: WorkflowContext](
        inner: SignalMatch[InnerCtx],
        convertEvent: WCEvent[InnerCtx] => WCEvent[OuterCtx],
    ) extends SignalMatch[OuterCtx] {
      def produceResult[R](signalDef: SignalDef[?, R]): SignalResult[WCEvent[OuterCtx], R] =
        inner.produceResult(signalDef) match {
          case SignalResult.Processed(resultIO) =>
            SignalResult.Processed(resultIO.map { result =>
              SignalResult.ProcessingResult(convertEvent(result.event), result.response)
            })
          case SignalResult.Redelivered(resp)   => SignalResult.Redelivered(resp)
          case SignalResult.UnexpectedSignal    => SignalResult.UnexpectedSignal
        }
    }
  }

  private def extractTypedResponse[Req, Resp](signalDef: SignalDef[Req, Resp], response: Any): Resp =
    signalDef.respCt
      .unapply(response)
      .getOrElse(
        throw new Exception(
          s"""The signal response type was different from the one expected based on SignalDef. This is probably a bug, please report it.
             |Response: ${response}
             |Expected: ${signalDef.respCt}""".stripMargin,
        ),
      )

  private class SignalVisitor[Ctx <: WorkflowContext, Resp, Err, Out <: WCState[Ctx], In, Req](
      wio: WIO[In, Err, Out, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      input: In,
      lastSeenState: WCState[Ctx],
  ) extends Visitor[Ctx, In, Err, Out](wio)
      with StrictLogging {
    override type Result = Option[SignalMatch[Ctx]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = {
      if signalDef.id == wio.sigDef.id then {
        val expectedReqOpt = wio.sigDef.reqCt.unapply(req)
        if expectedReqOpt.isEmpty then {
          logger.warn(
            s"""Request passed to signal handler doesn't have the type expected by the handler. This should not happen, please report it as a bug.
               |Request: ${req}
               |Expected type: ${wio.sigDef.reqCt}
               |""".stripMargin,
          )
        }
        expectedReqOpt.map(typedReq => SignalMatch.Fresh(wio, input, typedReq))
      } else None
    }

    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result       = None
    def onNoop(wio: WIO.End[Ctx]): Result                                  = None
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                   = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                 = None
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result   = None
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result               = None
    def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result = None

    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = {
      // Always recurse into original to find signal matches (handles all nested structures)
      recurse(wio.original, wio.input).flatMap {
        case fresh: SignalMatch.Fresh[Ctx, ?, ?, ?, ?, ?, ?] =>
          // Found a fresh match inside an Executed node
          wio.event match {
            case None            => Some(fresh) // No event stored, keep as Fresh
            case Some(storedEvt) =>
              // Event exists - detection must succeed, otherwise it's a bug
              val typedEvt = fresh.node.evtHandler
                .detect(storedEvt)
                .getOrElse(
                  throw new IllegalStateException(
                    s"Executed node has stored event but event handler failed to detect it. This is a bug in the library. Event: $storedEvt",
                  ),
                )
              Some(fresh.toRedelivered(typedEvt))
          }
        case other                                           => Some(other) // Keep Redelivered and Embedded as-is
      }
    }

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, input)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, input)
    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Result                                                    = recurse(wio.base, input)

    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base, wio.contramapInput(input))

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) =>
              // First check error handler for fresh match
              recurse(wio.handleError, (lastSeenState, err))
                // Then check base for redelivery
                .orElse(recurse(wio.base, input))
            case Right(_)  =>
              throw new IllegalStateException(
                "Base was executed but surrounding HandleErrorWith was still entered during evaluation. This is a bug in the library. Please report it to the maintainers.",
              )
          }
        case None               => recurse(wio.base, input)
      }
    }

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result = {
      val lastState = wio.history.lastOption.flatMap(_.output.toOption).getOrElse(lastSeenState)
      // First check current iteration for fresh match
      recurse(wio.current.wio, input, lastState)
        .orElse {
          // Then check history (most recent first) for redelivery matches
          wio.history.reverse.collectFirstSome(executed => recurse(executed, input, lastState))
        }
    }

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result =
      selectMatching(wio, input).flatMap(selected => recurse(selected.wio, selected.input))

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      =>
              throw new IllegalStateException(
                "First step of AndThen was executed with an error but surrounding AndThen was still entered during evaluation. This is a bug in the library. Please report it to the maintainers.",
              )
            case Right(value) =>
              // First check second for fresh match (precedence)
              recurse(wio.second, value, value)
                // Then check first for redelivery match
                .orElse(recurse(wio.first, input))
          }
        case None                => recurse(wio.first, input)
      }
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState = wio.embedding.unconvertStateUnsafe(lastSeenState)
      new SignalVisitor(wio.inner, signalDef, req, input, newState).run
        .map(innerMatch => SignalMatch.Embedded(innerMatch, wio.embedding.convertEvent))
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      wio.status match {
        case InterruptionStatus.Interrupted                               =>
          // Interruption was triggered - check interruption for fresh/redelivery, then base for redelivery
          recurse(wio.interruption, lastSeenState)
            .orElse(recurse(wio.base, input))
        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          // Check if base has already completed (never interrupted)
          wio.base.asExecuted.flatMap(_.output.toOption) match {
            case Some(_) =>
              // Base completed - only check base for redelivery, NOT interruption for fresh
              recurse(wio.base, input)
            case None    =>
              // Base not completed - check both interruption and base for fresh/redelivery
              recurse(wio.interruption, lastSeenState)
                .orElse(recurse(wio.base, input))
          }
      }
    }

    def onParallel[InterimState <: workflows4s.wio.WorkflowContext.State[Ctx]](
        wio: workflows4s.wio.WIO.Parallel[Ctx, In, Err, Out, InterimState],
    ): Result = {
      wio.elements.collectFirstSome(elem => recurse(elem.wio, input))
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result = recurse(wio.base, input)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Option[SignalMatch[Ctx]] = {
      for {
        unwrapped  <- wio.signalRouter.unwrap(signalDef, req, wio.interimState(input))
        elemWioOpt  = wio.state(input).get(unwrapped.elem)
        _           = if elemWioOpt.isEmpty then logger.warn(s"Tried to deliver a signal to an unrecognized element ${unwrapped.elem}")
        elemWio    <- elemWioOpt
        innerMatch <- new SignalVisitor(elemWio, unwrapped.sigDef, unwrapped.req, (), wio.initialElemState()).run
      } yield SignalMatch.Embedded(innerMatch, wio.eventEmbedding.convertEvent(unwrapped.elem, _))
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[I1, E1, O1, Ctx],
        in: I1,
        state: WCState[Ctx] = lastSeenState,
    ): SignalVisitor[Ctx, Resp, E1, O1, I1, Req]#Result =
      new SignalVisitor(wio, signalDef, req, in, state).run

  }

}
