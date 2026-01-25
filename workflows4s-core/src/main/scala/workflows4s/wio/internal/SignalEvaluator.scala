package workflows4s.wio.internal

import com.typesafe.scalalogging.StrictLogging
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus

object SignalEvaluator {

  // Type alias to hide internal type parameters - only TopIn and OutEvent matter externally
  type AnyMatch[TopIn, OutEvent] = SignalMatch[TopIn, OutEvent, ? <: WorkflowContext, ?, ?, ?, ?]

  // Properly typed abstraction for signal routing (e.g., ForEach wrapping)
  // Eliminates Any types from SignalMatch by parameterizing on InnerReq
  trait SignalRouting[TopIn, InnerReq] {
    val wrappedSignalDef: SignalDef[?, ?]
    def tryUnwrap[OuterReq, OuterResp](outerDef: SignalDef[OuterReq, OuterResp], request: OuterReq, input: TopIn): Option[InnerReq]
  }

  object SignalRouting {
    // ForEach routing implementation - cast is hidden here, verified by ClassTag in tryProduce
    class ForEach[TopIn, RouterIn, Elem, InnerReq, InnerResp](
        receiver: SignalRouter.Receiver[Elem, RouterIn],
        extractRouterInput: TopIn => RouterIn,
        expectedElem: Elem,
        innerSigDef: SignalDef[InnerReq, InnerResp],
    ) extends SignalRouting[TopIn, InnerReq] {
      val wrappedSignalDef: SignalDef[?, ?] = receiver.outerSignalDef(innerSigDef)

      def tryUnwrap[OuterReq, OuterResp](outerDef: SignalDef[OuterReq, OuterResp], request: OuterReq, input: TopIn): Option[InnerReq] = {
        val routerInput = extractRouterInput(input)
        receiver
          .unwrap(outerDef, request, routerInput)
          .filter(_.elem == expectedElem)
          .map(_.req.asInstanceOf[InnerReq]) // Cast hidden here - type verified via ClassTag in tryProduce
      }
    }
  }

  def getExpectedSignals(wio: WIO[?, ?, ?, ?], includeRedeliverable: Boolean = false): List[SignalDef[?, ?]] = {
    val visitor = new SignalVisitor(wio, lastSeenState = None)
    visitor.run
      .filter(m => includeRedeliverable || !m.isRedeliverable)
      .distinctBy(_.innerSignalDef.id) // Deduplicate by inner signal type for inspection
      .map(_.signalDef)
  }

  def handleSignal[Ctx <: WorkflowContext, Req, Resp, In <: WCState[Ctx], Out <: WCState[Ctx]](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[In, Nothing, Out, Ctx],
      state: In,
  ): SignalResult[WCEvent[Ctx], Resp] = {
    // Pass lastSeenState for error/interruption handlers
    val visitor  = new SignalVisitor[Ctx, In, Nothing, Out](wio, Some(state))
    val matches  = visitor.run
    val matching = matches.flatMap(_.tryProduce(signalDef, req, state))
    matching match {
      case Nil          => SignalResult.UnexpectedSignal
      case List(single) => single
      case multiple     =>
        // Multiple matches - this shouldn't happen in well-formed workflows
        // Take the first one (fresh signals should come before redeliverable ones due to traversal order)
        multiple.head
    }
  }

  // SignalMatch captures everything needed to produce a result
  // Type parameters:
  //   TopIn - input type passed from outside (to tryProduce)
  //   OutEvent - event type produced to outside
  //   LocalCtx - the WorkflowContext of the HandleSignal node
  //   LocalIn - input type expected by the HandleSignal node
  //   Req, Resp, Evt - signal handler types
  case class SignalMatch[TopIn, OutEvent, LocalCtx <: WorkflowContext, LocalIn, Req, Resp, Evt](
      node: WIO.HandleSignal[LocalCtx, LocalIn, ?, ?, Req, Resp, Evt],
      inputTransform: TopIn => LocalIn,
      eventTransform: WCEvent[LocalCtx] => OutEvent,
      eventUnconvert: OutEvent => Option[WCEvent[LocalCtx]], // Unconverts outer event back to local context
      storedEvent: Option[WCEvent[LocalCtx]] = None,         // Stored event for redelivery (detected lazily in tryProduce)
      routing: Option[SignalRouting[TopIn, Req]] = None,     // Optional signal routing (e.g., ForEach wrapping)
  ) extends StrictLogging {

    def signalDef: SignalDef[?, ?]      = routing.map(_.wrappedSignalDef).getOrElse(node.sigDef)
    def innerSignalDef: SignalDef[?, ?] = node.sigDef
    def isRedeliverable: Boolean        = storedEvent.isDefined

    def contramapInput[NewIn](f: NewIn => TopIn): SignalMatch[NewIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt] =
      copy(
        inputTransform = f.andThen(inputTransform),
        routing = routing.map(r =>
          new SignalRouting[NewIn, Req] {
            val wrappedSignalDef: SignalDef[?, ?]                                                                                      = r.wrappedSignalDef
            def tryUnwrap[OuterReq, OuterResp](outerDef: SignalDef[OuterReq, OuterResp], request: OuterReq, input: NewIn): Option[Req] =
              r.tryUnwrap(outerDef, request, f(input))
          },
        ),
      )

    def mapEvent[NewEvent](
        f: OutEvent => NewEvent,
        uf: NewEvent => Option[OutEvent],
    ): SignalMatch[TopIn, NewEvent, LocalCtx, LocalIn, Req, Resp, Evt] =
      copy(
        eventTransform = eventTransform.andThen(f),
        eventUnconvert = (ne: NewEvent) => uf(ne).flatMap(eventUnconvert),
      )

    def toRedeliverable(evt: WCEvent[LocalCtx]): SignalMatch[TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt] = {
      if isRedeliverable then return this // Already redeliverable
      copy(storedEvent = Some(evt))
    }

    /** Convert to redeliverable using an outer event, unconverting it to the local context */
    def toRedeliverableWithOuterEvent(outerEvt: OutEvent): SignalMatch[TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt] = {
      if isRedeliverable then return this // Already redeliverable
      eventUnconvert(outerEvt) match {
        case Some(localEvt) => copy(storedEvent = Some(localEvt))
        case None           =>
          // Event doesn't belong to this handler's context - shouldn't happen in well-formed workflows
          logger.warn(s"Failed to unconvert event for signal ${node.sigDef.name} - event may be from wrong context")
          this
      }
    }

    def withRouting(r: SignalRouting[TopIn, Req]): SignalMatch[TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt] =
      copy(routing = Some(r))

    def tryProduce[Req1, Resp1](outerSignalDef: SignalDef[Req1, Resp1], request: Req1, input: TopIn): Option[SignalResult[OutEvent, Resp1]] = {
      // Check if the outer signal def matches our (possibly wrapped) signal def
      if outerSignalDef.id != signalDef.id then return None

      // Try to extract the inner request - either via routing or direct match
      val unwrappedReqOpt: Option[Any] = routing match {
        case Some(r) => r.tryUnwrap(outerSignalDef, request, input)
        case None    => Some(request) // No routing - Req1 = Req (verified by ID match above)
      }
      if unwrappedReqOpt.isEmpty then return None

      // Verify type with ClassTag (handles both routing and direct cases)
      val typedReq   = node.sigDef.reqCt
        .unapply(unwrappedReqOpt.get)
        .getOrElse(
          throw new Exception(
            s"""Request passed to signal handler doesn't have the type expected by the handler. This is probably a bug, please report it.
               |Request: ${request}
               |Unwrapped: ${unwrappedReqOpt.get}
               |Expected type: ${node.sigDef.reqCt}""".stripMargin,
          ),
        )
      val localInput = inputTransform(input)

      storedEvent match {
        case Some(evt) =>
          // Redelivery - reconstruct response from stored event
          val typedStoredEvent = node.evtHandler
            .detect(evt)
            .getOrElse(
              throw new Exception(
                s"""Stored event type doesn't match the signal handler's expected type during redelivery. This is probably a bug, please report it.
                   |Signal: ${node.sigDef.name}
                   |Event: ${evt}""".stripMargin,
              ),
            )
          val response         = node.responseProducer(localInput, typedStoredEvent, typedReq)
          Some(SignalResult.Redelivered(extractTypedResponse(outerSignalDef, response)))

        case None =>
          // Fresh - produce new event
          val eventIO = node.sigHandler.handle(localInput, typedReq)
          Some(SignalResult.Processed(eventIO.map { evt =>
            val convertedEvent = eventTransform(node.evtHandler.convert(evt))
            val response       = node.responseProducer(localInput, evt, typedReq)
            SignalResult.ProcessingResult(convertedEvent, extractTypedResponse(outerSignalDef, response))
          }))
      }
    }
  }

  private object SignalMatch {
    def fresh[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx], Req, Resp, Evt](
        node: WIO.HandleSignal[Ctx, In, Out, Err, Req, Resp, Evt],
    ): SignalMatch[In, WCEvent[Ctx], Ctx, In, Req, Resp, Evt] =
      SignalMatch[In, WCEvent[Ctx], Ctx, In, Req, Resp, Evt](
        node = node,
        inputTransform = identity,
        eventTransform = identity,
        eventUnconvert = Some(_), // Identity: OutEvent = WCEvent[Ctx] = WCEvent[LocalCtx]
      )
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

  private class SignalVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      lastSeenState: Option[WCState[Ctx]] = None, // Last known state (for error/interruption handlers)
  ) extends Visitor[Ctx, In, Err, Out](wio)
      with StrictLogging {
    override type Result = List[AnyMatch[In, WCEvent[Ctx]]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result =
      List(SignalMatch.fresh(wio))

    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result       = Nil
    def onNoop(wio: WIO.End[Ctx]): Result                                  = Nil
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                   = Nil
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                 = Nil
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result   = Nil
    def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Result             = Nil
    def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result = Nil

    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = {
      // Use stored input from Executed node
      val storedInput: In1   = wio.input
      // Recurse into original
      val innerMatches       = new SignalVisitor(wio.original, lastSeenState).run
      // Transform input: outer In -> stored In1 (ignore outer input, use stored)
      val transformedMatches = innerMatches.map(_.contramapInput[In](_ => storedInput))
      // Transform fresh signals based on whether event is stored
      transformedMatches.flatMap { m =>
        if m.isRedeliverable then {
          // Already redeliverable - keep as-is
          List(m)
        } else {
          // Fresh signal inside Executed node - use stored event (properly typed as WCEvent[Ctx])
          wio.event match {
            case None            =>
              // No event stored - signal was executed but not redeliverable
              Nil
            case Some(storedEvt) =>
              // For direct HandleSignal nodes, LocalCtx = Ctx, so this is type-safe
              // For embedded nodes, we need event unconversion (handled via eventUnconvert)
              List(m.toRedeliverableWithOuterEvent(storedEvt))
          }
        }
      }
    }

    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base)
    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base)
    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base)
    override def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          = recurse(wio.base, wio.contramapInput)

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) =>
              // Base failed - collect from both handler (fresh) and base (redeliverable)
              val baseMatches    = recurse(wio.base)
              // For the handler, input is (State, Error) - use lastSeenState
              val handlerMatches = new SignalVisitor(wio.handleError, lastSeenState).run.map { m =>
                // Transform: In => (WCState[Ctx], ErrIn) using lastSeenState
                m.contramapInput[In](_ => (lastSeenState.get, err))
              }
              handlerMatches ++ baseMatches
            case Right(_)  =>
              recurse(wio.base)
          }
        case None               =>
          // Base not yet executed - collect from base only
          recurse(wio.base)
      }
    }

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Result = {
      // Collect from current iteration first (fresh signals have priority)
      val currentMatches = recurse(wio.current.wio)
      // Collect from history in reverse order (most recent first for redelivery)
      val historyMatches = wio.history.reverse.flatMap(executed => recurse(executed)).toList
      currentMatches ++ historyMatches
    }

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result = {
      // Only look in selected branches - unselected forks have no active signals
      wio.selected match {
        case Some(idx) =>
          val branch = wio.branches(idx)
          new SignalVisitor(branch.wio, lastSeenState).run.map { m =>
            m.contramapInput[In](in => branch.condition(in).get)
          }
        case None      =>
          // Fork not yet executed - signals inside are not active
          Nil
      }
    }

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      =>
              // First failed - only collect from first (redeliverable)
              recurse(wio.first)
            case Right(value) =>
              // First succeeded - collect from second (fresh) and first (redeliverable)
              val firstMatches  = recurse(wio.first)
              // Second expects Out1 as input - Out1 <: WCState[Ctx], so value IS the new lastSeenState
              val secondMatches = new SignalVisitor(wio.second, Some(value)).run.map { m =>
                m.contramapInput[In](_ => value)
              }
              secondMatches ++ firstMatches
          }
        case None                =>
          // First not yet executed - only collect from first
          recurse(wio.first)
      }
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      // For embedded, we need to convert state between contexts
      val innerState: Option[WCState[InnerCtx]]               = lastSeenState.map(wio.embedding.unconvertStateUnsafe)
      val innerMatches: List[AnyMatch[In, WCEvent[InnerCtx]]] = new SignalVisitor(wio.inner, innerState).run
      innerMatches.map(_.mapEvent(wio.embedding.convertEvent, wio.embedding.unconvertEvent))
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      // Helper to collect from interruption flow - interruption expects WCState[Ctx] as input
      def collectFromInterruption(): Result = {
        new SignalVisitor(wio.interruption, lastSeenState).run.map { m =>
          // Transform: In => WCState[Ctx] using lastSeenState directly
          m.contramapInput[In](_ => lastSeenState.get)
        }
      }

      wio.status match {
        case InterruptionStatus.Interrupted =>
          // Interruption triggered - collect from both interruption (fresh) and base (redeliverable)
          val interruptionMatches = collectFromInterruption()
          val baseMatches         = recurse(wio.base)
          interruptionMatches ++ baseMatches

        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          // Check if base has completed
          wio.base.asExecuted.flatMap(_.output.toOption) match {
            case Some(_) =>
              // Base completed - only collect from base (redeliverable)
              recurse(wio.base)
            case None    =>
              // Base not completed - collect from both
              val interruptionMatches = collectFromInterruption()
              val baseMatches         = recurse(wio.base)
              interruptionMatches ++ baseMatches
          }
      }
    }

    def onParallel[InterimState <: WCState[Ctx]](
        wio: WIO.Parallel[Ctx, In, Err, Out, InterimState],
    ): Result = {
      // Collect from all parallel elements - each element expects In as input
      wio.elements.flatMap(elem => recurse(elem.wio)).toList
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result =
      recurse(wio.base)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      // Collect signals from all active element WIOs
      val allMatches = wio.stateOpt
        .getOrElse(Map.empty)
        .toList
        .flatMap { case (elemId, elemWio) =>
          // Inner elements use initialElemState as their state context
          val innerState: Option[WCState[InnerCtx]]                = Some(wio.initialElemState())
          val innerMatches: List[AnyMatch[Any, WCEvent[InnerCtx]]] = new SignalVisitor(elemWio, innerState).run
          innerMatches.map { m =>
            // Create routing with proper types - innerSigDef carries the existential Req type
            val routing = new SignalRouting.ForEach(
              receiver = wio.signalRouter,
              extractRouterInput = (topIn: In) => wio.interimState(topIn),
              expectedElem = elemId,
              innerSigDef = m.node.sigDef,
            )
            m
              // Transform event from inner context to outer context
              .mapEvent[WCEvent[Ctx]](
                wio.eventEmbedding.convertEvent(elemId, _),
                (outerEvt: WCEvent[Ctx]) => wio.eventEmbedding.unconvertEvent(outerEvt).filter(_._1 == elemId).map(_._2),
              )
              // Transform input: In -> Unit (inner elements expect Unit as input)
              .contramapInput[In](_ => ())
              // Add signal routing - properly typed via m.node.sigDef
              .withRouting(routing)
          }
        }

      // Don't deduplicate here - each element needs its own match for execution
      // getExpectedSignals will deduplicate by signalDef.id for inspection
      allMatches
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], transformInput: In => I1): Result = {
      val inner = new SignalVisitor(wio, lastSeenState).run
      inner.map(_.contramapInput(transformInput))
    }

    def recurse[E1, O1 <: WCState[Ctx]](wio: WIO[In, E1, O1, Ctx]): Result =
      new SignalVisitor(wio, lastSeenState).run
  }

}
