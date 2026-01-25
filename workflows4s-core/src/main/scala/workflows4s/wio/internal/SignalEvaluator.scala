package workflows4s.wio.internal

import com.typesafe.scalalogging.StrictLogging
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus

object SignalEvaluator {

  // Type alias to hide internal type parameters - only TopIn and OutEvent matter externally
  type AnyMatch[TopIn, OutEvent] = SignalMatch[TopIn, OutEvent, ? <: WorkflowContext, ?, ?, ?, ?]

  def getExpectedSignals(wio: WIO[?, ?, ?, ?], includeRedeliverable: Boolean = false): List[SignalDef[?, ?]] = {
    // For inspection, we don't have runtime input or state
    val visitor = new SignalVisitor(wio, runtimeInput = None, lastSeenState = None)
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
    // For handleSignal, pass the input and lastSeenState (same as input initially)
    val visitor  = new SignalVisitor[Ctx, In, Nothing, Out](wio, Some(state), Some(state))
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
      eventUnconvert: OutEvent => Option[WCEvent[LocalCtx]],  // Unconverts outer event back to local context
      storedEvent: Option[WCEvent[LocalCtx]] = None,          // Stored event for redelivery (detected lazily in tryProduce)
      // Signal routing for ForEach - wraps signal def for inspection, unwraps request for execution
      signalDefWrapper: SignalDef[?, ?] => SignalDef[?, ?] = identity,
      requestUnwrapper: (SignalDef[?, ?], Any, TopIn) => Option[Any] = (_: SignalDef[?, ?], req: Any, _: TopIn) => Some(req),
  ) extends StrictLogging {

    def signalDef: SignalDef[?, ?]      = signalDefWrapper(node.sigDef)
    def innerSignalDef: SignalDef[?, ?] = node.sigDef
    def isRedeliverable: Boolean        = storedEvent.isDefined

    def contramapInput[NewIn](f: NewIn => TopIn): SignalMatch[NewIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt] =
      copy(
        inputTransform = f.andThen(inputTransform),
        requestUnwrapper = (sd, req, newIn) => requestUnwrapper(sd, req, f(newIn)),
      )

    def mapEvent[NewEvent](f: OutEvent => NewEvent, uf: NewEvent => Option[OutEvent]): SignalMatch[TopIn, NewEvent, LocalCtx, LocalIn, Req, Resp, Evt] =
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

    def withSignalRouting(
        wrapper: SignalDef[?, ?] => SignalDef[?, ?],
        unwrapper: (SignalDef[?, ?], Any, TopIn) => Option[Any],
    ): SignalMatch[TopIn, OutEvent, LocalCtx, LocalIn, Req, Resp, Evt] =
      copy(signalDefWrapper = wrapper, requestUnwrapper = unwrapper)

    def tryProduce[Req1, Resp1](outerSignalDef: SignalDef[Req1, Resp1], request: Req1, input: TopIn): Option[SignalResult[OutEvent, Resp1]] = {
      // Check if the outer signal def matches our (possibly wrapped) signal def
      if outerSignalDef.id != signalDef.id then return None

      // Try to unwrap the request (for ForEach routing)
      val unwrappedReqOpt = requestUnwrapper(outerSignalDef, request, input)
      if unwrappedReqOpt.isEmpty then return None

      // Try to cast to expected type
      val typedReqOpt = node.sigDef.reqCt.unapply(unwrappedReqOpt.get)
      if typedReqOpt.isEmpty then {
        logger.warn(
          s"""Request passed to signal handler doesn't have the type expected by the handler. This should not happen, please report it as a bug.
             |Request: ${request}
             |Unwrapped: ${unwrappedReqOpt.get}
             |Expected type: ${node.sigDef.reqCt}
             |""".stripMargin,
        )
        return None
      }
      val typedReq   = typedReqOpt.get
      val localInput = inputTransform(input)

      // Try to detect typed event from stored event
      val typedStoredEvent: Option[Evt] = storedEvent.flatMap(node.evtHandler.detect)

      storedEvent match {
        case Some(_) if typedStoredEvent.isDefined =>
          // Redelivery - reconstruct response from stored event
          val response = node.responseProducer(localInput, typedStoredEvent.get, typedReq)
          Some(SignalResult.Redelivered(extractTypedResponse(outerSignalDef, response)))

        case Some(_) =>
          // We have a stored event but couldn't detect it - this is unexpected for redelivery
          logger.warn(
            s"""Stored event type doesn't match the signal handler's expected type during redelivery.
               |This may indicate a workflow version mismatch.
               |Signal: ${node.sigDef.name}""".stripMargin,
          )
          None

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
        eventUnconvert = Some(_),  // Identity: OutEvent = WCEvent[Ctx] = WCEvent[LocalCtx]
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
      runtimeInput: Option[In] = None,              // Optional input for evaluating Fork conditions at runtime
      lastSeenState: Option[WCState[Ctx]] = None,   // Last known state (for error/interruption handlers)
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
      // Use stored input from Executed node - no cast needed
      val storedInput: In1 = wio.input
      // Recurse into original with the stored input
      val innerMatches = new SignalVisitor(wio.original, Some(storedInput), lastSeenState).run
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

    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, identity)
    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result =
      recurse(wio.base, identity)
    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base, identity)
    override def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          =
      recurse(wio.base, wio.contramapInput)

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) =>
              // Base failed - collect from both handler (fresh) and base (redeliverable)
              val baseMatches = recurse(wio.base, identity)
              // For the handler, input is (State, Error) - use lastSeenState directly
              val handlerInput: Option[(WCState[Ctx], ErrIn)] = lastSeenState.map(s => (s, err))
              val handlerMatches = new SignalVisitor(wio.handleError, handlerInput, lastSeenState).run.map { m =>
                // Transform: In => (WCState[Ctx], ErrIn) using lastSeenState
                m.contramapInput[In](_ => (lastSeenState.get, err))
              }
              handlerMatches ++ baseMatches
            case Right(_)  =>
              recurse(wio.base, identity)
          }
        case None               =>
          // Base not yet executed - collect from base only
          recurse(wio.base, identity)
      }
    }

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Result = {
      // Collect from current iteration first (fresh signals have priority)
      val currentMatches = recurse(wio.current.wio, identity)
      // Collect from history in reverse order (most recent first for redelivery)
      val historyMatches = wio.history.reverse.flatMap(executed => recurse(executed, identity)).toList
      currentMatches ++ historyMatches
    }

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result = {
      // Helper to collect from a branch - branch input type is the result of condition
      def collectFromBranch[BranchIn](branch: WIO.Branch[In, Err, Out, Ctx, BranchIn], branchInput: Option[BranchIn]): Result = {
        // Pass lastSeenState through unchanged - branch execution doesn't change the state
        new SignalVisitor(branch.wio, branchInput, lastSeenState).run.map { m =>
          m.contramapInput[In](in => branch.condition(in).get)
        }
      }

      // For Fork, we need to know which branch is selected
      wio.selected match {
        case Some(idx) =>
          val branch      = wio.branches(idx)
          val branchInput = runtimeInput.flatMap(in => branch.condition(in))
          collectFromBranch(branch, branchInput)
        case None      =>
          // No branch pre-selected - try to evaluate with runtime input if available
          runtimeInput match {
            case Some(input) =>
              // Evaluate branch conditions at runtime (like old SignalEvaluator)
              val selectedOpt = wio.branches.zipWithIndex.collectFirst {
                case (branch, idx) if branch.condition(input).isDefined =>
                  (branch, idx)
              }
              selectedOpt match {
                case Some((branch, _)) =>
                  val branchInput = branch.condition(input)
                  collectFromBranch(branch, branchInput)
                case None              => Nil // No branch matches - no signals expected
              }
            case None        =>
              // No runtime input and no pre-selected branch - no signals expected
              // (This is correct for getExpectedSignals - the fork hasn't been evaluated yet)
              Nil
          }
      }
    }

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      =>
              // First failed - only collect from first (redeliverable)
              recurse(wio.first, identity)
            case Right(value) =>
              // First succeeded - collect from second (fresh) and first (redeliverable)
              val firstMatches = recurse(wio.first, identity)
              // Second expects Out1 as input - Out1 <: WCState[Ctx], so value IS the new lastSeenState
              val secondMatches = new SignalVisitor(wio.second, Some(value), Some(value)).run.map { m =>
                m.contramapInput[In](_ => value)
              }
              secondMatches ++ firstMatches
          }
        case None                =>
          // First not yet executed - only collect from first
          recurse(wio.first, identity)
      }
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      // For embedded, we need to convert state between contexts
      val innerState: Option[WCState[InnerCtx]] = lastSeenState.map(wio.embedding.unconvertStateUnsafe)
      val innerMatches: List[AnyMatch[In, WCEvent[InnerCtx]]] = new SignalVisitor(wio.inner, runtimeInput, innerState).run
      innerMatches.map(_.mapEvent(wio.embedding.convertEvent, wio.embedding.unconvertEvent))
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      // Helper to collect from interruption flow - interruption expects WCState[Ctx] as input
      def collectFromInterruption(): Result = {
        new SignalVisitor(wio.interruption, lastSeenState, lastSeenState).run.map { m =>
          // Transform: In => WCState[Ctx] using lastSeenState directly
          m.contramapInput[In](_ => lastSeenState.get)
        }
      }

      wio.status match {
        case InterruptionStatus.Interrupted =>
          // Interruption triggered - collect from both interruption (fresh) and base (redeliverable)
          val interruptionMatches = collectFromInterruption()
          val baseMatches         = recurse(wio.base, identity)
          interruptionMatches ++ baseMatches

        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          // Check if base has completed
          wio.base.asExecuted.flatMap(_.output.toOption) match {
            case Some(_) =>
              // Base completed - only collect from base (redeliverable)
              recurse(wio.base, identity)
            case None    =>
              // Base not completed - collect from both
              val interruptionMatches = collectFromInterruption()
              val baseMatches         = recurse(wio.base, identity)
              interruptionMatches ++ baseMatches
          }
      }
    }

    def onParallel[InterimState <: WCState[Ctx]](
        wio: WIO.Parallel[Ctx, In, Err, Out, InterimState],
    ): Result = {
      // Collect from all parallel elements - each element expects In as input
      wio.elements.flatMap(elem => recurse(elem.wio, identity)).toList
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result =
      recurse(wio.base, identity)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      // Collect signals from all active element WIOs
      val allMatches = wio.stateOpt
        .getOrElse(Map.empty)
        .toList
        .flatMap { case (elemId, elemWio) =>
          // Inner elements use initialElemState as their state context
          val innerState: Option[WCState[InnerCtx]] = Some(wio.initialElemState())
          val innerMatches: List[AnyMatch[Any, WCEvent[InnerCtx]]] = new SignalVisitor(elemWio, None, innerState).run
          innerMatches.map { m =>
            m
              // Transform event from inner context to outer context
              .mapEvent[WCEvent[Ctx]](
                wio.eventEmbedding.convertEvent(elemId, _),
                (outerEvt: WCEvent[Ctx]) => wio.eventEmbedding.unconvertEvent(outerEvt).filter(_._1 == elemId).map(_._2),
              )
              // Transform input first: In -> Unit (inner elements expect Unit as input)
              .contramapInput[In](_ => ())
              // Then add signal routing with correct input type (In)
              .withSignalRouting(
                wrapper = wio.signalRouter.outerSignalDef(_),
                unwrapper = (outerSigDef, req, topIn: In) => {
                  // Try to unwrap and check if it's for this element
                  wio.signalRouter
                    .unwrap(outerSigDef.asInstanceOf[SignalDef[Any, Any]], req, wio.interimState(topIn))
                    .filter(_.elem == elemId)
                    .map(_.req)
                },
              )
          }
        }

      // Don't deduplicate here - each element needs its own match for execution
      // getExpectedSignals will deduplicate by signalDef.id for inspection
      allMatches
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[I1, E1, O1, Ctx],
        transformInput: In => I1,
    ): Result = {
      // Transform runtime input for nested Fork evaluation
      val transformedInput = runtimeInput.map(transformInput)
      // Pass lastSeenState unchanged - state tracking doesn't depend on input transformation
      val inner = new SignalVisitor(wio, transformedInput, lastSeenState).run
      inner.map(_.contramapInput(transformInput))
    }
  }

}
