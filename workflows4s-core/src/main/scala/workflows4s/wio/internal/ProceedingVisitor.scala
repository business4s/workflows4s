package workflows4s.wio.internal

import cats.implicits.{catsSyntaxOptionId, toTraverseOps}
import workflows4s.wio.WIO.HandleInterruption.{InterruptionStatus, InterruptionType}
import workflows4s.wio.*

abstract class ProceedingVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    wio: WIO[In, Err, Out, Ctx],
    input: In,
    lastSeenState: WCState[Ctx],
) extends Visitor[Ctx, In, Err, Out](wio) {
  type NewWf           = WFExecution[Ctx, In, Err, Out]
  override type Result = Option[NewWf]

  def onNoop(wio: WIO.End[Ctx]): Result                              = None
  def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = None
  def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Result         = None

  def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result =
    recurse(wio.base, input).map({
      case WFExecution.Complete(newWio) =>
        newWio.output match {
          case Left(err)    => WFExecution.complete(wio.copy(base = newWio), Left(err), newWio.input)
          case Right(value) => WFExecution.Partial(WIO.AndThen(newWio, wio.getNext(value)))
        }
      case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
    })

  def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result =
    recurse(wio.base, wio.contramapInput(input)).map({
      case WFExecution.Complete(newWio) =>
        WFExecution.complete(
          WIO.Transform(newWio, wio.contramapInput, wio.mapOutput),
          wio.mapOutput(input, newWio.output),
          input,
        )
      case WFExecution.Partial(newWio)  =>
        WFExecution.Partial[Ctx, In, Err, Out](WIO.Transform(newWio, wio.contramapInput, (_, out) => wio.mapOutput(input, out)))
    })

  def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
    wio.base.asExecuted match {
      case Some(baseExecuted) =>
        baseExecuted.output match {
          case Left(err) =>
            onHandleErrorWith(WIO.HandleErrorWith(baseExecuted, wio.handleError(lastSeenState, err), wio.handledErrorMeta, wio.newErrorMeta))
          case Right(_)  =>
            throw new IllegalStateException(
              "Base was successfully executed, but surrounding handle error was still evaluated. This is a bug.",
            )
        }
      case None               =>
        recurse(wio.base, input).map({
          case WFExecution.Complete(executedBase) =>
            executedBase.output match {
              case Left(err)    =>
                WFExecution.Partial(WIO.HandleErrorWith(executedBase, wio.handleError(lastSeenState, err), wio.handledErrorMeta, wio.newErrorMeta))
              case Right(value) => WFExecution.complete(wio.copy(base = executedBase), Right(value), executedBase.input)
            }
          case WFExecution.Partial(newWio)        => WFExecution.Partial(wio.copy(base = newWio))
        })
    }
  }

  def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result = {
    wio.base.asExecuted match {
      case Some(baseExecuted) =>
        baseExecuted.output match {
          case Left(err)    =>
            recurse(wio.handleError, (lastSeenState, err)).map(handlerResult => {
              def updateHandler(newHandler: WIO[(WCState[Ctx], ErrIn), Err, Out, Ctx]) = wio.copy(handleError = newHandler)
              handlerResult match {
                case WFExecution.Complete(newHandler) => WFExecution.complete(updateHandler(newHandler), newHandler.output, input)
                case WFExecution.Partial(newHandler)  => WFExecution.Partial(updateHandler(newHandler))
              }
            })
          case Right(value) => WFExecution.complete(wio, Right(value), input).some
        }
      case None               =>
        recurse(wio.base, input).map(baseResult => {
          def updateBase(newBase: WIO[In, ErrIn, Out, Ctx]) = wio.copy(base = newBase)
          baseResult match {
            case WFExecution.Complete(newWio) =>
              newWio.output match {
                case Left(_)      => WFExecution.Partial(updateBase(newWio))
                case Right(value) => WFExecution.complete(updateBase(newWio), Right(value), input)
              }
            case WFExecution.Partial(newWio)  => WFExecution.Partial(updateBase(newWio))
          }
        })
    }
  }

  def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
    wio.first.asExecuted match {
      case Some(firstExecuted) =>
        firstExecuted.output match {
          case Left(err)    => WFExecution.complete(wio, Left(err), input).some
          case Right(value) =>
            recurse(wio.second, value, value).map({
              case WFExecution.Complete(newWio) => WFExecution.complete(WIO.AndThen(wio.first, newWio), newWio.output, input)
              case WFExecution.Partial(newWio)  => WFExecution.Partial(WIO.AndThen(firstExecuted, newWio))
            })
        }
      case None                =>
        recurse(wio.first, input).map(result => WFExecution.Partial(WIO.AndThen(result.wio, wio.second)))
    }
  }

  def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result = {
    // TODO all the `.provideInput` here are not good, they enlarge the graph unnecessarily.
    //  alternatively we could maybe take the input from the last history entry
    val lastState = wio.history.lastOption.flatMap(_.output.toOption).getOrElse(lastSeenState)
    recurse(wio.current, input, lastState).map({
      case WFExecution.Complete(newWio) =>
        newWio.output match {
          case Left(err)    => WFExecution.complete(wio.copy(history = wio.history :+ newWio), Left(err), input)
          case Right(value) =>
            if (wio.isReturning) {
              WFExecution.Partial(wio.copy(current = wio.loop.provideInput(value), isReturning = false, history = wio.history :+ newWio))
            } else {
              wio.stopCondition(value) match {
                case Some(value1) =>
                  WFExecution.complete(
                    wio.copy(history = wio.history :+ newWio, current = WIO.Executed(wio.current, Right(value), input)),
                    Right(value1),
                    input,
                  )
                case None         =>
                  wio.onRestart match {
                    case Some(onRestart) =>
                      WFExecution.Partial(wio.copy(current = onRestart.provideInput(value), isReturning = true, history = wio.history :+ newWio))
                    case None            =>
                      WFExecution.Partial(wio.copy(current = wio.loop.provideInput(value), isReturning = true, history = wio.history :+ newWio))
                  }

              }
            }
        }
      case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(current = newWio))
    })
  }

  def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result = {
    def updateSelectedBranch[I](selected: Matching[I]): WIO.Fork[Ctx, In, Err, Out] = {
      wio.copy(
        branches = wio.branches.zipWithIndex.map((branch, idx) => {
          if (idx == selected.idx) WIO.Branch.selected(selected.input, selected.wio, branch.name)
          else branch
        }),
        selected = Some(selected.idx),
      )
    }

    wio.selected match {
      case Some(selectedIdx) =>
        val branch    = wio.branches(selectedIdx)
        val branchOut = branch.condition(input).get
        recurse(branch.wio(), branchOut).map({
          case WFExecution.Complete(wio) => WFExecution.complete(updateSelectedBranch(Matching(selectedIdx, branchOut, wio)), wio.output, input)
          case WFExecution.Partial(wio)  => WFExecution.Partial(updateSelectedBranch(Matching(selectedIdx, branchOut, wio)))
        })
      case None              =>
        selectMatching(wio, input).flatMap({ selected =>
          recurse(selected.wio, selected.input).map({
            case WFExecution.Complete(newWio) => WFExecution.complete(updateSelectedBranch(selected.copy(wio = newWio)), newWio.output, input)
            case WFExecution.Partial(newWio)  => WFExecution.Partial(updateSelectedBranch(selected.copy(wio = newWio)))
          })
        })
    }
  }

  // proceed on interruption will be needed for timeouts
  def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
    def runBase: Result = recurse(wio.base, input)
      .map({
        case WFExecution.Complete(newWio) => WFExecution.complete(wio.copy(base = newWio), newWio.output, newWio.input)
        case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
      })

    def runInterruption: Result = {
      val lastBaseState = GetStateEvaluator.extractLastState(wio.base, input, lastSeenState)
      recurse(wio.interruption, lastBaseState.getOrElse(lastSeenState))
        .map(interruptionResult => {
          val newStatus: InterruptionStatus.Interrupted.type | InterruptionStatus.TimerStarted.type =
            wio.status match {
              case InterruptionStatus.Interrupted  => InterruptionStatus.Interrupted
              case InterruptionStatus.TimerStarted => InterruptionStatus.Interrupted
              case InterruptionStatus.Pending      =>
                wio.interruptionType match {
                  case InterruptionType.Signal => InterruptionStatus.Interrupted
                  case InterruptionType.Timer  => InterruptionStatus.TimerStarted
                }
            }
          interruptionResult match {
            case WFExecution.Complete(newInterruptionWio) =>
              WFExecution.complete(wio.copy(interruption = newInterruptionWio, status = newStatus), newInterruptionWio.output, input)
            case WFExecution.Partial(newInterruptionWio)  =>
              val newBase = newStatus match {
                case InterruptionStatus.Interrupted  => WIO.Discarded(wio.base, input)
                case InterruptionStatus.TimerStarted => wio.base
              }
              WFExecution.Partial(wio.copy(base = newBase, newInterruptionWio, status = newStatus))
          }
        })
    }

    wio.status match {
      case InterruptionStatus.Interrupted  => runInterruption
      case InterruptionStatus.TimerStarted => runInterruption.orElse(runBase)
      case InterruptionStatus.Pending      => runInterruption.orElse(runBase)
    }
  }

  override def onParallel[InterimState <: WCState[Ctx]](
      wio: WIO.Parallel[Ctx, In, Err, Out, InterimState],
  ): Option[NewWf] = {
    // Try to handle the event in one branch – the first branch that accepts it.
    // We update that branch and leave the others unchanged.
    var branchHandled: Option[(Int, WIO[In, Err, WCState[Ctx], Ctx])] = None

    val updatedElements = wio.elements.zipWithIndex.map { case (elem, idx) =>
      if (branchHandled.isEmpty) {
        // Try to process the event on this branch using our recurse helper.
        recurse(elem.wio, input, lastSeenState) match {
          case Some(newBranch) =>
            branchHandled = Some((idx, newBranch.wio))
            // Replace the branch with the updated branch.
            elem.copy(wio = newBranch.wio)
          case None            =>
            // This branch does not accept the event.
            elem
        }
      } else {
        // A branch has already handled the event; leave this branch unchanged.
        elem
      }
    }
    if (branchHandled.isEmpty) return None

    val maybeStates: Either[Err, Seq[Option[WCState[Ctx]]]] = updatedElements.traverse(elem => elem.wio.asExecuted.traverse(_.output))
    val newWio                                              = wio.copy(elements = updatedElements)
    maybeStates match {
      case Left(err)   => Some(WFExecution.complete(newWio, Left(err), input))
      case Right(opts) =>
        opts.sequence match {
          case Some(states) => Some(WFExecution.complete(newWio, Right(wio.formResult(states)), input))
          case None         => Some(WFExecution.Partial(newWio))
        }
    }
  }

  def handleCheckpointBase[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Option[NewWf] = {
    recurse(wio.base, input, lastSeenState)
      .map({
        case WFExecution.Complete(newWio) =>
          newWio.output match {
            case Left(err) => WFExecution.complete(wio.copy(base = newWio), Left(err), input)
            case Right(_)  => WFExecution.Partial(wio.copy(base = newWio))
          }
        case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
      })
  }

  def recurse[I1, E1, O1 <: WCState[Ctx]](
      wio: WIO[I1, E1, O1, Ctx],
      in: I1,
      state: WCState[Ctx] = lastSeenState,
  ): Option[WFExecution[Ctx, I1, E1, O1]]
}
