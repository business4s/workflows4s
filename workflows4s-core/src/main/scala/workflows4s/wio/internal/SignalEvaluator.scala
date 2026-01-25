package workflows4s.wio.internal

import com.typesafe.scalalogging.StrictLogging
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus

object SignalEvaluator {

  def getExpectedSignals(wio: WIO[?, ?, ?, ?], includeRedeliverable: Boolean = false): List[SignalDef[?, ?]] = {
    // For inspection, we don't have runtime input - rely on wio.selected for Fork
    val visitor = new SignalVisitor(wio, runtimeInput = None)
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
    // For handleSignal, pass the input so Fork conditions can be evaluated at runtime
    val visitor  = new SignalVisitor(wio, Some(state))
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
  // Uses existential types internally but exposes clean interface
  sealed trait SignalMatch[TopIn, OutEvent] {
    def signalDef: SignalDef[?, ?]
    def innerSignalDef: SignalDef[?, ?] // The original signal def before any wrapping (for deduplication)
    def isRedeliverable: Boolean
    def tryProduce[Req, Resp](signalDef: SignalDef[Req, Resp], request: Req, input: TopIn): Option[SignalResult[OutEvent, Resp]]
    def contramapInput[NewIn](f: NewIn => TopIn): SignalMatch[NewIn, OutEvent]
    def mapEvent[NewEvent](f: OutEvent => NewEvent): SignalMatch[TopIn, NewEvent]
    // Try to convert to redeliverable by matching the stored event
    def toRedeliverable(storedEvt: Any): Option[SignalMatch[TopIn, OutEvent]]
    // Add signal routing (for ForEach) - wrapper for inspection, unwrapper for execution
    // unwrapper receives (outerSignalDef, outerRequest, input) and returns Option[innerRequest]
    def withSignalRouting(
        wrapper: SignalDef[?, ?] => SignalDef[?, ?],
        unwrapper: (SignalDef[?, ?], Any, TopIn) => Option[Any],
    ): SignalMatch[TopIn, OutEvent]
  }

  private object SignalMatch {
    // Implementation with all type parameters hidden
    // rawStoredEvent is the untyped event for inspection (isRedeliverable check)
    // storedEvent is the typed event for execution (tryProduce)
    case class Impl[TopIn, OutEvent, LocalIn, Req, Resp, Evt](
        node: WIO.HandleSignal[?, LocalIn, ?, ?, Req, Resp, Evt],
        inputTransform: TopIn => LocalIn,
        eventTransform: Any => OutEvent, // Any because we need to handle both Evt and WCEvent types
        storedEvent: Option[Evt],
        rawStoredEvent: Option[Any],     // Raw event for isRedeliverable check (may not match handler type)
        // Signal routing for ForEach - wraps signal def for inspection, unwraps request for execution
        signalDefWrapper: SignalDef[?, ?] => SignalDef[?, ?] = identity[SignalDef[?, ?]],
        requestUnwrapper: (SignalDef[?, ?], Any, TopIn) => Option[Any] = (_: SignalDef[?, ?], req: Any, _: TopIn) => Some(req),
    ) extends SignalMatch[TopIn, OutEvent]
        with StrictLogging {

      def signalDef: SignalDef[?, ?]      = signalDefWrapper(node.sigDef)
      def innerSignalDef: SignalDef[?, ?] = node.sigDef
      def isRedeliverable: Boolean        = rawStoredEvent.isDefined

      def contramapInput[NewIn](f: NewIn => TopIn): SignalMatch[NewIn, OutEvent] =
        copy(
          inputTransform = f.andThen(inputTransform),
          requestUnwrapper = (sd, req, newIn) => requestUnwrapper(sd, req, f(newIn)),
        )

      def mapEvent[NewEvent](f: OutEvent => NewEvent): SignalMatch[TopIn, NewEvent] =
        copy(eventTransform = eventTransform.andThen(f))

      def toRedeliverable(storedEvt: Any): Option[SignalMatch[TopIn, OutEvent]] = {
        if isRedeliverable then return Some(this) // Already redeliverable
        // Mark as redeliverable with raw event - we'll detect type at tryProduce time
        Some(copy(rawStoredEvent = Some(storedEvt)))
      }

      def withSignalRouting(
          wrapper: SignalDef[?, ?] => SignalDef[?, ?],
          unwrapper: (SignalDef[?, ?], Any, TopIn) => Option[Any],
      ): SignalMatch[TopIn, OutEvent] =
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
        val typedReq    = typedReqOpt.get
        val localInput  = inputTransform(input)

        // Check if we have a typed stored event, or try to detect from raw
        val typedStoredEvent: Option[Evt] = storedEvent.orElse {
          rawStoredEvent.flatMap { rawEvt =>
            val detectFn = node.evtHandler.detect.asInstanceOf[Any => Option[Evt]]
            detectFn(rawEvt)
          }
        }

        typedStoredEvent match {
          case Some(evt) =>
            // Redelivery - reconstruct response from stored event
            val response = node.responseProducer(localInput, evt, typedReq)
            Some(SignalResult.Redelivered(extractTypedResponse(outerSignalDef, response)))

          case None if rawStoredEvent.isDefined =>
            // We have a raw event but couldn't detect it - this is unexpected for redelivery
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

    def fresh[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx], Req, Resp, Evt](
        node: WIO.HandleSignal[Ctx, In, Out, Err, Req, Resp, Evt],
    ): SignalMatch[In, WCEvent[Ctx]] =
      Impl[In, WCEvent[Ctx], In, Req, Resp, Evt](
        node = node,
        inputTransform = identity,
        eventTransform = _.asInstanceOf[WCEvent[Ctx]], // identity, but needs cast due to type erasure
        storedEvent = None,
        rawStoredEvent = None,
      )

    def redeliverable[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx], Req, Resp, Evt](
        node: WIO.HandleSignal[Ctx, In, Out, Err, Req, Resp, Evt],
        storedEvent: Evt,
    ): SignalMatch[In, WCEvent[Ctx]] =
      Impl[In, WCEvent[Ctx], In, Req, Resp, Evt](
        node = node,
        inputTransform = identity,
        eventTransform = _.asInstanceOf[WCEvent[Ctx]],
        storedEvent = Some(storedEvent),
        rawStoredEvent = Some(storedEvent),
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
      runtimeInput: Option[In] = None, // Optional input for evaluating Fork conditions at runtime
  ) extends Visitor[Ctx, In, Err, Out](wio)
      with StrictLogging {
    override type Result = List[SignalMatch[In, WCEvent[Ctx]]]

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
      // Recurse into original to find signal matches
      // Note: In1 = In at runtime since this visitor was created for a WIO[In, ...]
      val innerMatches = new SignalVisitor(wio.original).run.map(_.contramapInput((in: In) => in.asInstanceOf[In1]))
      // Transform fresh signals based on whether event is stored
      innerMatches.flatMap { m =>
        if m.isRedeliverable then {
          // Already redeliverable - keep as-is
          List(m)
        } else {
          // Fresh signal inside Executed node
          wio.event match {
            case None            =>
              // No event stored - signal was executed but not redeliverable
              Nil
            case Some(storedEvt) =>
              // Try to convert to redeliverable using the stored event
              m.toRedeliverable(storedEvt).toList
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
              val baseMatches    = recurse(wio.base, identity)
              // For the handler, input is (State, Error) - we capture the error at runtime
              val handlerMatches = new SignalVisitor(wio.handleError).run.map { m =>
                // Transform: In => (WCState[Ctx], ErrIn)
                // We capture the error value in the closure
                m.contramapInput[In](in => (in.asInstanceOf[WCState[Ctx]], err)) // TODO this is wrong, we need to track last seen state
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
      // For Fork, we need to know which branch is selected
      wio.selected match {
        case Some(idx) =>
          val branch = wio.branches(idx)
          // Branch is selected - recurse with input transformation
          new SignalVisitor(branch.wio).run.map { m =>
            m.contramapInput[In](in => branch.condition(in).get)
          }
        case None      =>
          // No branch pre-selected - try to evaluate with runtime input if available
          runtimeInput match {
            case Some(input) =>
              // Evaluate branch conditions at runtime (like old SignalEvaluator)
              val selectedOpt = wio.branches.zipWithIndex.collectFirst {
                case (branch, idx) if branch.condition(input).isDefined =>
                  (branch, idx, branch.condition(input).get)
              }
              selectedOpt match {
                case Some((branch, _, branchInput)) =>
                  // Pass None for runtimeInput - nested Forks will rely on selected being set
                  new SignalVisitor(branch.wio).run.map { m =>
                    m.contramapInput[In](in => branch.condition(in).get)
                  }
                case None                           =>
                  // No branch matches - no signals expected
                  Nil
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
              val firstMatches  = recurse(wio.first, identity)
              // Second expects Out1 as input - pass value as runtimeInput for nested Fork evaluation
              val secondMatches = new SignalVisitor(wio.second, Some(value)).run.map { m =>
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
      val innerMatches: List[SignalMatch[In, WCEvent[InnerCtx]]] = new SignalVisitor(wio.inner).run
      innerMatches.map(_.mapEvent(wio.embedding.convertEvent))
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      wio.status match {
        case InterruptionStatus.Interrupted =>
          // Interruption triggered - collect from both interruption (fresh) and base (redeliverable)
          val interruptionMatches = new SignalVisitor(wio.interruption).run.map { m =>
            // Interruption expects WCState[Ctx] as input
            m.contramapInput[In](_.asInstanceOf[WCState[Ctx]])
          }
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
              val interruptionMatches = new SignalVisitor(wio.interruption).run.map { m =>
                m.contramapInput[In](_.asInstanceOf[WCState[Ctx]])
              }
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
          val innerMatches: List[SignalMatch[Any, WCEvent[InnerCtx]]] = new SignalVisitor(elemWio).run
          innerMatches.map { m =>
            m
              // Transform event from inner context to outer context
              .mapEvent[WCEvent[Ctx]](wio.eventEmbedding.convertEvent(elemId, _))
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
    ): List[SignalMatch[In, WCEvent[Ctx]]] = {
      // Transform runtime input for nested Fork evaluation
      val transformedInput                           = runtimeInput.map(transformInput)
      val inner: List[SignalMatch[I1, WCEvent[Ctx]]] = new SignalVisitor(wio, transformedInput).run
      inner.map(_.contramapInput(transformInput))
    }
  }

}
