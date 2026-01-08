package workflows4s.example.withdrawal

import workflows4s.example.withdrawal.WithdrawalEvent.{MoneyLocked, WithdrawalAccepted, WithdrawalRejected}
import workflows4s.example.withdrawal.WithdrawalService.ExecutionResponse
import workflows4s.example.withdrawal.WithdrawalSignal.{CancelWithdrawal, CreateWithdrawal, ExecutionCompleted}
import workflows4s.example.withdrawal.checks.*
import workflows4s.runtime.instanceengine.Effect
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.wio
import workflows4s.wio.internal.WorkflowEmbedding
import workflows4s.wio.{SignalDef, WorkflowContext}

import java.time.Duration

class WithdrawalWorkflow[
    F[_],
    Ctx <: WorkflowContext { type Eff[A] = F[A]; type Event = WithdrawalEvent; type State = WithdrawalData },
    ChecksCtx <: WorkflowContext { type Eff[A] = F[A]; type Event = ChecksEvent; type State = ChecksState },
](
    ctx: Ctx,
    service: WithdrawalService[F],
    checksEngine: ChecksEngine[F, ChecksCtx],
)(using effect: Effect[F]) {

  import ctx.WIO

  /** Embedding for ChecksEngine workflow into WithdrawalWorkflow.
    *
    * This embedding bridges two workflow contexts with the same effect type F but different path-dependent types. The cast in `convertState` is
    * required because Scala's match types cannot reduce when the scrutinee is an abstract type parameter (T), even though all possible cases are
    * covered.
    */
  val checksEmbedding: WorkflowEmbedding[ChecksCtx, Ctx, WithdrawalData.Validated] =
    new WorkflowEmbedding[ChecksCtx, Ctx, WithdrawalData.Validated] {
      override type InnerEvent = ChecksEvent
      override type OuterEvent = WithdrawalEvent
      override type InnerState = ChecksState
      override type OuterState = WithdrawalData

      override def convertEvent(e: ChecksEvent): WithdrawalEvent =
        WithdrawalEvent.ChecksRun(e)

      override def unconvertEvent(e: WithdrawalEvent): Option[ChecksEvent] = e match {
        case WithdrawalEvent.ChecksRun(inner) => Some(inner)
        case _                                => None
      }

      override type OutputState[T <: ChecksState] <: WithdrawalData = T match {
        case ChecksState.InProgress => WithdrawalData.Checking
        case ChecksState.Decided    => WithdrawalData.Checked
      }

      override def convertState[T <: ChecksState](s: T, input: WithdrawalData.Validated): OutputState[T] = (s match {
        case x: ChecksState.InProgress => input.checking(x)
        case x: ChecksState.Decided    => input.checked(x)
      }).asInstanceOf[OutputState[T]]

      override def unconvertState(outerState: WithdrawalData): Option[ChecksState] =
        outerState match {
          case _: WithdrawalData.Validated => Some(ChecksState.Empty)
          case x: WithdrawalData.Checking  => Some(x.checkResults)
          case x: WithdrawalData.Checked   => Some(x.checkResults)
          case _                           => None
        }
    }

  def workflow: WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
    (for {
      _ <- validate
      _ <- calculateFees
      _ <- putFundsOnHold
      _ <- runChecks
      _ <- execute
      s <- releaseFunds
    } yield s)
      .handleErrorWith(cancelFundsIfNeeded)

  def workflowDeclarative: WIO.Initial =
    (
      (
        validate >>>
          calculateFees >>>
          putFundsOnHold >>>
          runChecks >>>
          execute
      ).interruptWith(handleCancellation)
        >>> releaseFunds
    ).handleErrorWith(cancelFundsIfNeeded) >>> WIO.noop()

  private def validate: WIO[Any, WithdrawalRejection.InvalidInput, WithdrawalData.Initiated] =
    WIO
      .handleSignal(WithdrawalWorkflow.Signals.createWithdrawal)
      .using[Any]
      .purely { (_, signal) =>
        if signal.amount > 0 then WithdrawalAccepted(signal.txId, signal.amount, signal.recipient)
        else WithdrawalRejected("Amount must be positive")
      }
      .handleEventWithError { (_, event) =>
        event match {
          case WithdrawalAccepted(txId, amount, recipient) => Right(WithdrawalData.Initiated(txId, amount, recipient))
          case WithdrawalRejected(error)                   => Left(WithdrawalRejection.InvalidInput(error))
        }
      }
      .voidResponse
      .autoNamed

  private def calculateFees: WIO[WithdrawalData.Initiated, Nothing, WithdrawalData.Validated] = WIO
    .runIO[WithdrawalData.Initiated](state => service.calculateFees(state.amount).map(WithdrawalEvent.FeeSet.apply))
    .handleEvent { (state, event) => state.validated(event.fee) }
    .autoNamed()

  private def putFundsOnHold: WIO[WithdrawalData.Validated, WithdrawalRejection.NotEnoughFunds, WithdrawalData.Validated] =
    WIO
      .runIO[WithdrawalData.Validated] { state =>
        service.putMoneyOnHold(state.amount).map {
          case Left(WithdrawalService.NotEnoughFunds()) => WithdrawalEvent.MoneyLocked.NotEnoughFunds()
          case Right(_)                                 => WithdrawalEvent.MoneyLocked.Success()
        }
      }
      .handleEventWithError { (state, evt) =>
        evt match {
          case MoneyLocked.Success()        => Right(state)
          case MoneyLocked.NotEnoughFunds() => Left(WithdrawalRejection.NotEnoughFunds())
        }
      }
      .autoNamed()

  private def runChecks: WIO[WithdrawalData.Validated, WithdrawalRejection.RejectedInChecks, WithdrawalData.Checked] = {
    val doRunChecks: WIO[WithdrawalData.Validated, Nothing, WithdrawalData.Checked] = {
      val innerWIO = checksEngine.runChecks
        .transformInput((x: WithdrawalData.Validated) => ChecksInput(x, service.getChecks()))
      ctx.WIO.embed(innerWIO.asInstanceOf)(checksEmbedding.asInstanceOf).asInstanceOf[WIO[WithdrawalData.Validated, Nothing, WithdrawalData.Checked]]
    }

    val actOnDecision = WIO.pure
      .makeFrom[WithdrawalData.Checked]
      .errorOpt(_.checkResults.decision match {
        case Decision.RejectedBySystem()   => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedBySystem()   => None
        case Decision.RejectedByOperator() => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedByOperator() => None
      })
      .autoNamed

    doRunChecks >>> actOnDecision
  }

  private def execute: WIO[WithdrawalData.Checked, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    initiateExecution >>> awaitExecutionCompletion

  // This could use retries once we have them
  private def initiateExecution: WIO[WithdrawalData.Checked, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .runIO[WithdrawalData.Checked](s => service.initiateExecution(s.netAmount, s.recipient).map(WithdrawalEvent.ExecutionInitiated.apply))
      .handleEventWithError { (s, event) =>
        event.response match {
          case ExecutionResponse.Accepted(externalId) => Right(s.executed(externalId))
          case ExecutionResponse.Rejected(error)      => Left(WithdrawalRejection.RejectedByExecutionEngine(error))
        }
      }
      .autoNamed()
      .retryIn(_ => WithdrawalWorkflow.executionRetryDelay)

  private def awaitExecutionCompletion: WIO[WithdrawalData.Executed, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .handleSignal(WithdrawalWorkflow.Signals.executionCompleted)
      .using[WithdrawalData.Executed]
      .purely((_, sig) => WithdrawalEvent.ExecutionCompleted(sig))
      .handleEventWithError((s, e: WithdrawalEvent.ExecutionCompleted) =>
        e.status match {
          case ExecutionCompleted.Succeeded => Right(s)
          case ExecutionCompleted.Failed    => Left(WithdrawalRejection.RejectedByExecutionEngine("Execution failed"))
        },
      )
      .voidResponse
      .done

  private def releaseFunds: WIO[WithdrawalData.Executed, Nothing, WithdrawalData.Completed] =
    WIO
      .runIO[WithdrawalData.Executed](st => service.releaseFunds(st.amount).as(WithdrawalEvent.MoneyReleased()))
      .handleEvent((st, _) => st.completed())
      .autoNamed()

  private def cancelFundsIfNeeded: WIO[(WithdrawalData, WithdrawalRejection), Nothing, WithdrawalData.Completed.Failed] = {
    WIO
      .runIO[(WithdrawalData, WithdrawalRejection)] { case (_, r) =>
        r match {
          case WithdrawalRejection.InvalidInput(error)              =>
            WithdrawalEvent.RejectionHandled(error).pure[F]
          case WithdrawalRejection.NotEnoughFunds()                 =>
            WithdrawalEvent.RejectionHandled("Not enough funds on the user's account").pure[F]
          case WithdrawalRejection.RejectedInChecks()               =>
            service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled("Transaction rejected in checks"))
          case WithdrawalRejection.RejectedByExecutionEngine(error) =>
            service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled(error))
          case WithdrawalRejection.Cancelled(operatorId, comment)   =>
            service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled(s"Cancelled by ${operatorId}. Comment: ${comment}"))
        }
      }
      .handleEvent((_: (WithdrawalData, WithdrawalRejection), evt) => WithdrawalData.Completed.Failed(evt.error))
      .autoNamed()
  }

  private def handleCancellation = {
    WIO.interruption
      .throughSignal(WithdrawalWorkflow.Signals.cancel)
      .handleAsync { (state, signal) =>
        def ok = WithdrawalEvent.WithdrawalCancelledByOperator(signal.operatorId, signal.comment).pure[F]
        state match {
          case _: WithdrawalData.Empty     => ok
          case _: WithdrawalData.Initiated => ok
          case _: WithdrawalData.Validated => ok
          case _: WithdrawalData.Checking  => ok
          case _: WithdrawalData.Checked   => ok
          case _: WithdrawalData.Executed  =>
            if signal.acceptStartedExecution then ok
            else
              effect.raiseError(
                new Exception("To cancel transaction that has been already executed, this fact has to be explicitly accepted in the request."),
              )
          case _: WithdrawalData.Completed => effect.raiseError(new Exception(s"Unexpected state for cancellation: $state"))
        }
      }
      .handleEventWithError((_, evt) => Left(WithdrawalRejection.Cancelled(evt.operatorId, evt.comment)))
      .voidResponse
      .done
  }

}

object WithdrawalWorkflow {

  val executionRetryDelay = Duration.ofMinutes(2)

  object Signals {
    val createWithdrawal   = SignalDef[CreateWithdrawal, Unit]()
    val executionCompleted = SignalDef[ExecutionCompleted, Unit]()
    val cancel             = SignalDef[CancelWithdrawal, Unit]()
  }

}
