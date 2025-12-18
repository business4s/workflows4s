package workflows4s.wio.internal

import cats.implicits.{catsSyntaxOptionId, toTraverseOps}
import workflows4s.wio.WIO.HandleInterruption.{InterruptionStatus, InterruptionType}
import workflows4s.wio.*
import workflows4s.wio.WIO.Loop.State
import workflows4s.runtime.instanceengine.Effect

abstract class ProceedingVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    wio: WIO[F, In, Err, Out, Ctx],
    input: In,
    lastSeenState: WCState[Ctx],
    index: Int,
)(using protected val E: Effect[F])
    extends Visitor[F, Ctx, In, Err, Out](wio) {

  type NewWf           = WFExecution[F, Ctx, In, Err, Out]
  override type Result = Option[NewWf]

  def onNoop(wio: WIO.End[F, Ctx]): Result                              = None
  def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result = None
  def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result         = None

  def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result = {
    recurse(wio.base, input).map({
      case WFExecution.Complete(newWio) =>
        newWio.output match {
          case Left(err)    => WFExecution.complete(wio.copy(base = newWio), Left(err), newWio.input, index)
          case Right(value) => WFExecution.Partial(WIO.AndThen(newWio, wio.getNext(value)))
        }
      case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
    })
  }

  def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result = {
    recurse(wio.base, wio.contramapInput(input)).map({
      case WFExecution.Complete(newWio) =>
        WFExecution.complete(
          WIO.Transform(newWio, wio.contramapInput, wio.mapOutput),
          wio.mapOutput(input, newWio.output),
          input,
          newWio.index,
        )
      case WFExecution.Partial(newWio)  =>
        WFExecution.Partial[F, Ctx, In, Err, Out](WIO.Transform(newWio, wio.contramapInput, (_, out) => wio.mapOutput(input, out)))
    })
  }

  def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
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
              case Right(value) => WFExecution.complete(wio.copy(base = executedBase), Right(value), executedBase.input, executedBase.index)
            }
          case WFExecution.Partial(newWio)        => WFExecution.Partial(wio.copy(base = newWio))
        })
    }
  }

  def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result = {
    wio.base.asExecuted match {
      case Some(baseExecuted) =>
        baseExecuted.output match {
          case Left(err)    =>
            val state        = baseExecuted.lastState(lastSeenState).getOrElse(lastSeenState)
            val handlerIndex = baseExecuted.index + 1
            recurse(wio.handleError, (state, err), index = handlerIndex).map(handlerResult => {
              def updateHandler(newHandler: WIO[F, (WCState[Ctx], ErrIn), Err, Out, Ctx]) = wio.copy(handleError = newHandler)
              handlerResult match {
                case WFExecution.Complete(newHandler) => WFExecution.complete(updateHandler(newHandler), newHandler.output, input, newHandler.index)
                case WFExecution.Partial(newHandler)  => WFExecution.Partial(updateHandler(newHandler))
              }
            })
          case Right(value) => WFExecution.complete(wio, Right(value), input, baseExecuted.index).some
        }
      case None               =>
        recurse(wio.base, input).map(baseResult => {
          def updateBase(newBase: WIO[F, In, ErrIn, Out, Ctx]) = wio.copy(base = newBase)
          baseResult match {
            case WFExecution.Complete(newWio) =>
              newWio.output match {
                case Left(_)      => WFExecution.Partial(updateBase(newWio))
                case Right(value) => WFExecution.complete(updateBase(newWio), Right(value), input, index)
              }
            case WFExecution.Partial(newWio)  => WFExecution.Partial(updateBase(newWio))
          }
        })
    }
  }

  def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result = {
    wio.first.asExecuted match {
      case Some(firstExecuted) =>
        firstExecuted.output match {
          case Left(err)    => WFExecution.complete(wio, Left(err), input, firstExecuted.index).some
          case Right(value) =>
            val secondIndex = firstExecuted.index + 1
            recurse(wio.second, value, value, secondIndex).map({
              case WFExecution.Complete(newWio) =>
                WFExecution.complete(WIO.AndThen(wio.first, newWio), newWio.output, input, newWio.index)
              case WFExecution.Partial(newWio)  => WFExecution.Partial(WIO.AndThen(firstExecuted, newWio))
            })
        }
      case None                =>
        recurse(wio.first, input).map(result => WFExecution.Partial(WIO.AndThen(result.wio, wio.second)))
    }
  }

  def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result = {
    val lastHistoryState = wio.history.flatMap(_.lastState(lastSeenState)).lastOption.getOrElse(lastSeenState)
    val nextIndex        = wio.history.lastOption.map(result => result.index + 1).getOrElse(index)
    wio.current match {
      case State.Finished(_)          =>
        None
      case State.Forward(currentWio)  =>
        recurse(currentWio, input, lastHistoryState, nextIndex).map({
          case WFExecution.Complete(newWio) =>
            newWio.output match {
              case Left(err)    => WFExecution.complete(wio.copy(history = wio.history :+ newWio), Left(err), input, newWio.index)
              case Right(value) =>
                wio.stopCondition(value) match {
                  case Right(value1)  =>
                    WFExecution.complete(
                      wio.copy(
                        history = wio.history :+ newWio,
                        current = WIO.Loop.State.Finished(WIO.Executed(currentWio, Right(value), input, newWio.index)),
                      ),
                      Right(value1),
                      input,
                      newWio.index,
                    )
                  case Left(returnIn) =>
                    WFExecution.Partial(
                      wio.copy(current = WIO.Loop.State.Backward(wio.onRestart.provideInput(returnIn)), history = wio.history :+ newWio),
                    )
                }

            }
          case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(current = State.Forward(newWio)))
        })
      case State.Backward(currentWio) =>
        recurse(currentWio, input, lastHistoryState, nextIndex).map({
          case WFExecution.Complete(newWio) =>
            newWio.output match {
              case Left(err)    => WFExecution.complete(wio.copy(history = wio.history :+ newWio), Left(err), input, newWio.index)
              case Right(value) =>
                WFExecution.Partial(wio.copy(current = WIO.Loop.State.Forward(wio.body.provideInput(value)), history = wio.history :+ newWio))
            }
          case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(current = State.Backward(newWio)))
        })
    }
  }

  def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result = {
    def updateSelectedBranch[I](selected: Matching[I]): WIO.Fork[F, Ctx, In, Err, Out] = {
      wio.copy(
        branches = wio.branches.zipWithIndex.map((branch, idx) => {
          if idx == selected.idx then WIO.Branch.selected(selected.input, selected.wio, branch.name).asInstanceOf[WIO.Branch[F, In, Err, Out, Ctx, ?]]
          else branch
        }),
        selected = Some(selected.idx),
      )
    }

    wio.selected match {
      case Some(selectedIdx) =>
        val branch    = wio.branches(selectedIdx)
        val branchOut = branch.condition(input).get
        recurse(branch.wio, branchOut).map({
          case WFExecution.Complete(wio) =>
            WFExecution.complete(updateSelectedBranch(Matching(selectedIdx, branchOut, wio)), wio.output, input, wio.index)
          case WFExecution.Partial(wio)  => WFExecution.Partial(updateSelectedBranch(Matching(selectedIdx, branchOut, wio)))
        })
      case None              =>
        selectMatching(wio, input).flatMap({ selected =>
          recurse(selected.wio, selected.input).map({
            case WFExecution.Complete(newWio) =>
              WFExecution.complete(updateSelectedBranch(selected.copy(wio = newWio)), newWio.output, input, newWio.index)
            case WFExecution.Partial(newWio)  => WFExecution.Partial(updateSelectedBranch(selected.copy(wio = newWio)))
          })
        })
    }
  }

  def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result = {
    def runBase: Result = recurse(wio.base, input)
      .map({
        case WFExecution.Complete(newWio) => WFExecution.complete(wio.copy(base = newWio), newWio.output, newWio.input, index + 1)
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
              WFExecution.complete(wio.copy(interruption = newInterruptionWio, status = newStatus), newInterruptionWio.output, input, index + 1)
            case WFExecution.Partial(newInterruptionWio)  =>
              val newBase = newStatus match {
                case InterruptionStatus.Interrupted  => WIO.Discarded(wio.base, input)
                case InterruptionStatus.TimerStarted => wio.base
              }
              WFExecution.Partial(wio.copy(base = newBase, interruption = newInterruptionWio, status = newStatus))
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
      wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState],
  ): Option[NewWf] = {
    var branchHandled: Option[(Int, WIO[F, In, Err, WCState[Ctx], Ctx])] = None
    val nextIndex                                                        = GetIndexEvaluator.findMaxIndex(wio).map(_ + 1).getOrElse(index)

    val updatedElements = wio.elements.zipWithIndex.map { case (elem, idx) =>
      if branchHandled.isEmpty then {
        recurse(elem.wio, input, lastSeenState, nextIndex) match {
          case Some(newBranch) =>
            branchHandled = Some((idx, newBranch.wio))
            elem.copy(wio = newBranch.wio)
          case None            => elem
        }
      } else elem
    }
    if branchHandled.isEmpty then return None

    val maybeStates: Either[Err, Seq[Option[WCState[Ctx]]]] = updatedElements.traverse(elem => elem.wio.asExecuted.traverse(_.output))
    val newWio                                              = wio.copy(elements = updatedElements)
    val maybeLastIndex                                      = branchHandled.flatMap(_._2.asExecuted.map(_.index))

    maybeStates match {
      case Left(err)   =>
        Some(WFExecution.complete(newWio, Left(err), input, maybeLastIndex.getOrElse(index)))
      case Right(opts) =>
        opts.sequence match {
          case Some(states) =>
            Some(WFExecution.complete(newWio, Right(wio.formResult(states)), input, maybeLastIndex.getOrElse(index)))
          case None         => Some(WFExecution.Partial(newWio))
        }
    }
  }

  def handleCheckpointBase[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Option[NewWf] = {
    recurse(wio.base, input, lastSeenState)
      .map({
        case WFExecution.Complete(newWio) =>
          newWio.output match {
            case Left(err) => WFExecution.complete(wio.copy(base = newWio), Left(err), input, newWio.index)
            case Right(_)  => WFExecution.Partial(wio.copy(base = newWio))
          }
        case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
      })
  }

  override def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Option[NewWf] =
    recurse(wio.base, input).map({
      case WFExecution.Complete(newWio) => WFExecution.complete(wio.copy(base = newWio), newWio.output, newWio.input, newWio.index)
      case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
    })

  def recurse[I1, E1, O1 <: WCState[Ctx]](
      wio: WIO[F, I1, E1, O1, Ctx],
      in: I1,
      state: WCState[Ctx] = lastSeenState,
      index: Int = index,
  ): Option[WFExecution[F, Ctx, I1, E1, O1]]
}
