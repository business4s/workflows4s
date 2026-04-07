package workflows4s.wio.internal

import cats.Functor
import com.typesafe.scalalogging.StrictLogging
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus

object SignalEvaluator {

  type AnyMatch[F[_], TopIn, OutEvent, TopState] = SignalMatch[F, TopIn, OutEvent, ? <: WorkflowContext, ?, ?, ?, ?, TopState]

  // Adapts outer signal requests to inner handlers (e.g., ForEach elements receive unwrapped requests)
  // Takes (TopIn, TopState) to ensure it derives data from the same source as transform
  case class SignalRouting[TopIn, TopState, InnerReq](
      wrappedSignalDef: SignalDef[?, ?],
      unwrap: (SignalDef[?, ?], Any, TopIn, TopState) => Option[InnerReq],
  ) {
    def contramapInput[NewIn](f: NewIn => TopIn): SignalRouting[NewIn, TopState, InnerReq] =
      copy(unwrap = (sigDef, req, newIn, state) => unwrap(sigDef, req, f(newIn), state))

    def contramapState[NewState](f: NewState => TopState): SignalRouting[TopIn, NewState, InnerReq] =
      copy(unwrap = (sigDef, req, in, newState) => unwrap(sigDef, req, in, f(newState)))
  }

  object SignalRouting {
    def forEach[TopIn, TopState, RouterIn, Elem, InnerReq, InnerResp](
        receiver: SignalRouter.Receiver[Elem, RouterIn],
        extractRouterInput: (TopIn, TopState) => RouterIn,
        expectedElem: Elem,
        innerSigDef: SignalDef[InnerReq, InnerResp],
    ): SignalRouting[TopIn, TopState, InnerReq] = SignalRouting(
      wrappedSignalDef = receiver.outerSignalDef(innerSigDef),
      unwrap = (outerDef, request, input, state) =>
        // Cast needed: outerDef and request have matching types, verified by signal ID match before unwrap is called
        receiver
          .unwrap(outerDef.asInstanceOf[SignalDef[Any, Any]], request, extractRouterInput(input, state))
          .filter(_.elem == expectedElem)
          .map(_.req.asInstanceOf[InnerReq]),
    )
  }

  def getExpectedSignals(wio: WIO[?, ?, ?, ?], includeRedeliverable: Boolean = false): List[SignalDef[?, ?]] = {
    getExpectedSignalsImpl(wio, includeRedeliverable)
  }

  private def getExpectedSignalsImpl[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      includeRedeliverable: Boolean,
  ): List[SignalDef[?, ?]] = {
    // liftEffect is never invoked — only signalDef/isRedeliverable are read from matches
    new SignalVisitor[WCEffect[Ctx], Ctx, In, Err, Out](wio, [A] => (fa: WCEffect[Ctx][A]) => fa).run
      .filter(m => includeRedeliverable || !m.isRedeliverable)
      .distinctBy(_.innerSignalDef.id)
      .map(_.signalDef)
  }

  def getAllSignalDefs(wio: WIO[?, ?, ?, ?]): List[SignalDef[?, ?]] = {
    new FullScanSignalVisitor(wio).run.distinctBy(_.id)
  }

  def handleSignal[F[_]: Functor, Ctx <: WorkflowContext, Req, Resp, In <: WCState[Ctx], Out <: WCState[Ctx]](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[In, Nothing, Out, Ctx],
      state: In,
      liftEffect: WCEffectLift[Ctx, F],
  ): SignalResult[F, WCEvent[Ctx], Resp] = {
    val matches = new SignalVisitor[F, Ctx, In, Nothing, Out](wio, liftEffect).run
      .flatMap(_.tryProduce(signalDef, req, state, state))

    // Fresh signals come before redeliverable ones due to traversal order
    matches.headOption.getOrElse(SignalResult.UnexpectedSignal())
  }

  /** Captures a signal handler location in the WIO tree plus transformations needed to execute it.
    *
    * Type parameters:
    *   - F: The effect type
    *   - TopIn/TopState: The input/state types at the level where tryProduce will be called
    *   - OutEvent: The event type after transformation
    *   - LocalCtx/LocalIn: The context and input types of the actual signal handler
    *   - Req/Resp/Evt: Signal handler types
    */
  case class SignalMatch[F[_], TopIn, OutEvent, LocalCtx <: WorkflowContext, LocalIn, Req, Resp, Evt, TopState](
      node: WIO.HandleSignal[LocalCtx, LocalIn, ?, ?, Req, Resp, Evt],
      transform: (TopIn, TopState) => (LocalIn, WCState[LocalCtx]),
      eventTransform: WCEvent[LocalCtx] => OutEvent,
      eventUnconvert: OutEvent => Option[WCEvent[LocalCtx]],
      liftEffect: WCEffectLift[LocalCtx, F],
      storedEvent: Option[WCEvent[LocalCtx]] = None,
      routing: Option[SignalRouting[TopIn, TopState, Req]] = None,
  ) extends StrictLogging {

    def signalDef: SignalDef[?, ?]      = routing.map(_.wrappedSignalDef).getOrElse(node.sigDef)
    def innerSignalDef: SignalDef[?, ?] = node.sigDef
    def isRedeliverable: Boolean        = storedEvent.isDefined

    /** Transform input type */
    def contramapInput[NewIn](f: NewIn => TopIn): SignalMatch[F, NewIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt, TopState] =
      copy(
        transform = (newIn, state) => transform(f(newIn), state),
        routing = routing.map(_.contramapInput(f)),
      )

    /** Transform state type */
    def contramapState[NewState](f: NewState => TopState): SignalMatch[F, TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt, NewState] =
      this.copy(
        transform = (in, newState) => transform(in, f(newState)),
        routing = routing.map(_.contramapState(f)),
      )

    /** Retype by providing a function to derive old input/state from new input/state. Both transform and routing are updated using the same
      * deriveInputState function, ensuring they stay in sync.
      */
    def retype[NewIn, NewTopState](
        deriveInputState: (NewIn, NewTopState) => (TopIn, TopState),
    ): SignalMatch[F, NewIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt, NewTopState] =
      this.copy(
        transform = (newIn, newState) => {
          val (oldIn, oldState) = deriveInputState(newIn, newState)
          transform(oldIn, oldState)
        },
        routing = routing.map { r =>
          r.copy(
            unwrap = (sigDef, req, newIn, newState) => {
              val (oldIn, oldState) = deriveInputState(newIn, newState)
              r.unwrap(sigDef, req, oldIn, oldState)
            },
          )
        },
      )

    def mapEvent[NewEvent](
        f: OutEvent => NewEvent,
        uf: NewEvent => Option[OutEvent],
    ): SignalMatch[F, TopIn, NewEvent, LocalCtx, LocalIn, Req, Resp, Evt, TopState] =
      copy(eventTransform = eventTransform.andThen(f), eventUnconvert = uf(_).flatMap(eventUnconvert))

    def toRedeliverable(outerEvt: OutEvent): SignalMatch[F, TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt, TopState] = {
      if isRedeliverable then return this
      eventUnconvert(outerEvt) match {
        case Some(localEvt) => copy(storedEvent = Some(localEvt))
        case None           =>
          throw new Exception(s"Failed to unconvert event for signal ${node.sigDef.name} - event type mismatch. This shouldn't happen.")
      }
    }

    def withRouting(r: SignalRouting[TopIn, TopState, Req]): SignalMatch[F, TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt, TopState] =
      copy(routing = Some(r))

    def mapEffect[G[_]](f: [A] => F[A] => G[A]): SignalMatch[G, TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt, TopState] =
      this.copy(liftEffect = [A] => (fa: WCEffect[LocalCtx][A]) => f(liftEffect(fa)))

    def tryProduce[Req1, Resp1](
        outerSignalDef: SignalDef[Req1, Resp1],
        request: Req1,
        input: TopIn,
        topState: TopState,
    )(using Functor[F]): Option[SignalResult[F, OutEvent, Resp1]] =
      for {
        _           <- Option.when(outerSignalDef.id == signalDef.id)(())
        innerReq    <- unwrapRequest(request, input, topState)
        typedReq     = verifyRequestType(innerReq)
        (localIn, _) = transform(input, topState)
      } yield produceResult(typedReq, localIn, outerSignalDef)

    private def unwrapRequest(request: Any, input: TopIn, state: TopState): Option[Any] =
      routing match {
        case Some(r) => r.unwrap(signalDef, request, input, state)
        case None    => Some(request)
      }

    private def verifyRequestType(request: Any): Req =
      node.sigDef.reqCt.unapply(request).getOrElse {
        throw new Exception(s"Request type mismatch for signal ${node.sigDef.name}. Expected: ${node.sigDef.reqCt}, got: $request")
      }

    private def produceResult[Resp1](typedReq: Req, localInput: LocalIn, outerSignalDef: SignalDef[?, Resp1])(using Functor[F]): SignalResult[F, OutEvent, Resp1] =
      storedEvent match {
        case Some(evt) => redeliverFromStoredEvent(typedReq, localInput, evt, outerSignalDef)
        case None      => handleFreshSignal(typedReq, localInput, outerSignalDef)
      }

    private def redeliverFromStoredEvent[Resp1](
        typedReq: Req,
        localInput: LocalIn,
        evt: WCEvent[LocalCtx],
        outerSignalDef: SignalDef[?, Resp1],
    ): SignalResult[F, OutEvent, Resp1] = {
      val typedEvent = node.evtHandler.detect(evt).getOrElse {
        throw new Exception(s"Stored event type mismatch during redelivery for signal ${node.sigDef.name}")
      }
      val response   = node.responseProducer(localInput, typedEvent, typedReq)
      SignalResult.Redelivered(extractTypedResponse(outerSignalDef, response))
    }

    private def handleFreshSignal[Resp1](
        typedReq: Req,
        localInput: LocalIn,
        outerSignalDef: SignalDef[?, Resp1],
    )(using Functor[F]): SignalResult[F, OutEvent, Resp1] = {
      SignalResult.Processed(Functor[F].map(liftEffect(node.sigHandler.handle(localInput, typedReq))) { evt =>
        val convertedEvent = eventTransform(node.evtHandler.convert(evt))
        val response       = node.responseProducer(localInput, evt, typedReq)
        SignalResult.ProcessingResult(convertedEvent, extractTypedResponse(outerSignalDef, response))
      })
    }
  }

  private object SignalMatch {
    def fresh[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx], Req, Resp, Evt](
        node: WIO.HandleSignal[Ctx, In, Out, Err, Req, Resp, Evt],
        liftEffect: WCEffectLift[Ctx, F],
    ): SignalMatch[F, In, WCEvent[Ctx], Ctx, In, Req, Resp, Evt, WCState[Ctx]] =
      SignalMatch(
        node,
        transform = (in, state) => (in, state),
        eventTransform = identity,
        eventUnconvert = Some(_),
        liftEffect = liftEffect,
      )
  }

  private def extractTypedResponse[Resp](signalDef: SignalDef[?, Resp], response: Any): Resp =
    signalDef.respCt.unapply(response).getOrElse {
      throw new Exception(s"Response type mismatch for signal ${signalDef.name}. Expected: ${signalDef.respCt}, got: $response")
    }

  /** Traverses the WIO tree to collect signal handlers.
    *
    * State is tracked/accumulated through the currentState function in SignalMatch, similar to how GetStateEvaluator tracks lastSeenState.
    */
  private class SignalVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      liftEffect: WCEffectLift[Ctx, F],
  ) extends Visitor[Ctx, In, Err, Out](wio)
      with StrictLogging {
    override type Result = List[AnyMatch[F, In, WCEvent[Ctx], WCState[Ctx]]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = List(SignalMatch.fresh(wio, liftEffect))

    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result       = Nil
    def onNoop(wio: WIO.End[Ctx]): Result                                  = Nil
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                   = Nil
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                 = Nil
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result   = Nil
    def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Result             = Nil
    def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result = Nil

    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = {
      val innerMatches = new SignalVisitor(wio.original, liftEffect).run
        .map(_.contramapInput[In](_ => wio.input))

      innerMatches.flatMap { m =>
        if m.isRedeliverable then List(m)
        else wio.event.map(m.toRedeliverable).toList
      }
    }

    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base)
    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result =
      recurse(wio.base)
    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base)
    override def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          =
      recurse(wio.base, wio.contramapInput)

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) =>
              // Error handler receives (lastState, error) as input
              // State should be extracted from base execution
              val handlerMatches = new SignalVisitor(wio.handleError, liftEffect).run
                .map { m =>
                  m.retype[In, WCState[Ctx]](
                    deriveInputState = (in, topState) => {
                      val stateFromBase: WCState[Ctx] = GetStateEvaluator.extractLastState(wio.base, in, topState)
                      ((stateFromBase, err), stateFromBase)
                    },
                  )
                }
              handlerMatches ++ recurse(wio.base)
            case Right(_)  => recurse(wio.base)
          }
        case None               => recurse(wio.base)
      }
    }

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Result = {
      // Current iteration first (fresh), then history reversed (most recent redeliverable first)
      recurse(wio.current.wio) ++ wio.history.reverse.flatMap(recurse(_)).toList
    }

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result = {
      // Only selected branch has active signals
      wio.selected match {
        case Some(idx) =>
          val branch = wio.branches(idx)
          new SignalVisitor(branch.wio, liftEffect).run.map(_.contramapInput[In](in => branch.condition(in).get))
        case None      => Nil
      }
    }

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      => recurse(wio.first)
            case Right(value) =>
              // Second step: input is `value`, state should be computed from first's execution
              val stateFromFirst: WCState[Ctx] = value
              val secondMatches                = new SignalVisitor(wio.second, liftEffect).run.map { m =>
                m.retype[In, WCState[Ctx]](
                  deriveInputState = (_, _) => (value, stateFromFirst),
                )
              }
              secondMatches ++ recurse(wio.first)
          }
        case None                => recurse(wio.first)
      }
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val innerLift: WCEffectLift[InnerCtx, F] = [A] => (fa: WCEffect[InnerCtx][A]) => liftEffect(wio.embedding.liftInnerEffect(fa))
      val innerMatches                                     = new SignalVisitor(wio.inner, innerLift).run
      innerMatches.map { m =>
        m.mapEvent(wio.embedding.convertEvent, wio.embedding.unconvertEvent)
          .retype[In, WCState[Ctx]](
            deriveInputState = (in, topState) => (in, wio.embedding.unconvertStateUnsafe(topState)),
          )
      }
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      def interruptionMatches(): Result =
        new SignalVisitor(wio.interruption, liftEffect).run.map { m =>
          // Retype from WCState[Ctx] input to In input, computing state from base execution
          m.retype[In, WCState[Ctx]](
            deriveInputState = (in, topState) => {
              val stateFromBase: WCState[Ctx] = GetStateEvaluator.extractLastState(wio.base, in, topState)
              (stateFromBase, stateFromBase)
            },
          )
        }

      wio.status match {
        case InterruptionStatus.Interrupted =>
          interruptionMatches() ++ recurse(wio.base)

        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          val baseCompleted = wio.base.asExecuted.exists(_.output.isRight)
          if baseCompleted then recurse(wio.base)
          else interruptionMatches() ++ recurse(wio.base)
      }
    }

    def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[Ctx, In, Err, Out, InterimState]): Result =
      wio.elements.flatMap(elem => recurse(elem.wio)).toList

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result =
      recurse(wio.base)

    // No deduplication here - each element needs its own match; getExpectedSignals deduplicates for inspection
    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      // InnerCtx is in scope here, so we can properly type elemInitialState
      val elemInitialState: WCState[InnerCtx] = wio.initialElemState()

      val innerLift: WCEffectLift[InnerCtx, F] = [A] => (fa: WCEffect[InnerCtx][A]) => liftEffect(wio.liftInnerEffect(fa))

      wio.stateOpt.getOrElse(Map.empty).toList.flatMap { case (elemId, elemWio) =>
        val innerMatches: Seq[AnyMatch[F, Any, WCEvent[InnerCtx], WCState[InnerCtx]]] = new SignalVisitor(elemWio, innerLift).run
        // The inner matches have TopIn = Any due to type erasure, but we know it's Unit
        innerMatches.map { inner =>
          // Step 1: Retype from element context (Unit, WCState[InnerCtx]) to ForEach context (In, WCState[Ctx])
          val retyped = inner.retype[In, WCState[Ctx]](
            deriveInputState = (_, _) => ((), elemInitialState),
          )

          // Step 2: Add routing for signal unwrapping
          val routing     = SignalRouting.forEach(
            receiver = wio.signalRouter,
            extractRouterInput = (in: In, _: WCState[Ctx]) => wio.interimState(in),
            expectedElem = elemId,
            innerSigDef = inner.node.sigDef,
          )
          val withRouting = retyped.withRouting(routing)

          // Step 3: Transform events
          withRouting
            .mapEvent(
              f = (evt: WCEvent[InnerCtx]) => wio.eventEmbedding.convertEvent(elemId, evt),
              uf = (evt: WCEvent[Ctx]) => wio.eventEmbedding.unconvertEvent(evt).filter(_._1 == elemId).map(_._2),
            )
        }
      }
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], transformInput: In => I1): Result =
      new SignalVisitor(wio, liftEffect).run.map(_.contramapInput(transformInput))

    private def recurse[E1, O1 <: WCState[Ctx]](wio: WIO[In, E1, O1, Ctx]): Result =
      new SignalVisitor(wio, liftEffect).run
  }

  private class FullScanSignalVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = List[SignalDef[?, ?]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = List(wio.sigDef)

    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result       = Nil
    def onNoop(wio: WIO.End[Ctx]): Result                                  = Nil
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                   = Nil
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                 = Nil
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result   = Nil
    def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Result             = Nil
    def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result = Nil

    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result =
      new FullScanSignalVisitor(wio.original).run

    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result =
      recurse(wio.base)

    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result =
      recurse(wio.base)

    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Result = recurse(wio.base)

    override def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base)

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result =
      recurse(wio.base) ++ recurse(wio.handleError)

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Result =
      recurse(wio.current.wio) ++ recurse(wio.onRestart) ++ wio.history.flatMap(h => recurse(h)).toList

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result =
      wio.branches.flatMap(branch => new FullScanSignalVisitor(branch.wio).run).toList

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result =
      recurse(wio.first) ++ recurse(wio.second)

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result =
      new FullScanSignalVisitor(wio.inner).run

    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result =
      recurse(wio.base) ++ recurse(wio.interruption)

    def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[Ctx, In, Err, Out, InterimState]): Result =
      wio.elements.flatMap(elem => recurse(elem.wio)).toList

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result =
      recurse(wio.base)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      val innerDefs = new FullScanSignalVisitor(wio.elemWorkflow).run
      innerDefs.map(wio.signalRouter.outerSignalDef(_))
    }

    private def recurse(wio: WIO[?, ?, ?, ?]): List[SignalDef[?, ?]] =
      new FullScanSignalVisitor(wio).run
  }

}
