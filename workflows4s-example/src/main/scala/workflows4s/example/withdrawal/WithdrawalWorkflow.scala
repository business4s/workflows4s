package workflows4s.example.withdrawal

import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxEitherId}
import workflows4s.example.withdrawal.WithdrawalEvent.{MoneyLocked, WithdrawalAccepted, WithdrawalRejected}
import workflows4s.example.withdrawal.WithdrawalService.ExecutionResponse
import workflows4s.example.withdrawal.WithdrawalSignal.{CancelWithdrawal, CreateWithdrawal, ExecutionCompleted}
import workflows4s.example.withdrawal.WithdrawalWorkflow.{Signals, checksEmbedding}
import workflows4s.example.withdrawal.checks.*
import workflows4s.wio
import workflows4s.wio.internal.WorkflowEmbedding
import workflows4s.cats.IOWorkflowContext
import workflows4s.cats.CatsEffect.given
import workflows4s.wio.SignalDef

import java.time.Duration
object WithdrawalWorkflow {

  val executionRetryDelay = Duration.ofMinutes(2)

  object Context extends IOWorkflowContext {
    override type Event = WithdrawalEvent
    override type State = WithdrawalData
  }

  object Signals {
    val createWithdrawal   = SignalDef[CreateWithdrawal, Unit]()
    val executionCompleted = SignalDef[ExecutionCompleted, Unit]()
    val cancel             = SignalDef[CancelWithdrawal, Unit]()
  }

  val checksEmbedding = new WorkflowEmbedding[ChecksEngine.Context.Ctx, WithdrawalWorkflow.Context.Ctx, WithdrawalData.Validated] {
    override def convertEvent(e: ChecksEvent): WithdrawalEvent = WithdrawalEvent.ChecksRun(e)

    override def unconvertEvent(e: WithdrawalEvent): Option[ChecksEvent] = e match {
      case WithdrawalEvent.ChecksRun(inner) => Some(inner)
      case _                                => None
    }

    override type OutputState[T <: ChecksState] <: WithdrawalData = T match {
      case ChecksState.InProgress => WithdrawalData.Checking
      case ChecksState.Decided    => WithdrawalData.Checked
    }

    override def convertState[T <: ChecksState](s: T, input: WithdrawalData.Validated): OutputState[T] = s match {
      case x: ChecksState.InProgress => input.checking(x)
      case x: ChecksState.Decided    => input.checked(x)
    }

    override def unconvertState(outerState: WithdrawalData): Option[ChecksState] = outerState match {
      case _: WithdrawalData.Validated => Some(ChecksState.Empty)
      case x: WithdrawalData.Checking  => Some(x.checkResults)
      case x: WithdrawalData.Checked   => Some(x.checkResults)
      case _                           => None
    }
  }

}

class WithdrawalWorkflow(service: WithdrawalService, checksEngine: ChecksEngine) {

  import WithdrawalWorkflow.Context.WIO

  val workflow: WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
    (for {
      _ <- validate
      _ <- calculateFees
      _ <- putFundsOnHold
      _ <- runChecks
      _ <- execute
      s <- releaseFunds
    } yield s)
      .handleErrorWith(cancelFundsIfNeeded)

  val workflowDeclarative: WIO.Initial =
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
      .handleSignal(Signals.createWithdrawal)
      .using[Any]
      .purely { (_, signal) =>
        if signal.amount > 0 then WithdrawalAccepted(signal.txId, signal.amount, signal.recipient)
        else WithdrawalRejected("Amount must be positive")
      }
      .handleEventWithError { (_, event) =>
        event match {
          case WithdrawalAccepted(txId, amount, recipient) => WithdrawalData.Initiated(txId, amount, recipient).asRight
          case WithdrawalRejected(error)                   => WithdrawalRejection.InvalidInput(error).asLeft
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
      .runIO[WithdrawalData.Validated](state =>
        service
          .putMoneyOnHold(state.amount)
          .map({
            case Left(WithdrawalService.NotEnoughFunds()) => WithdrawalEvent.MoneyLocked.NotEnoughFunds()
            case Right(_)                                 => WithdrawalEvent.MoneyLocked.Success()
          }),
      )
      .handleEventWithError { (state, evt) =>
        evt match {
          case MoneyLocked.Success()        => state.asRight
          case MoneyLocked.NotEnoughFunds() => WithdrawalRejection.NotEnoughFunds().asLeft
        }
      }
      .autoNamed()

  private def runChecks: WIO[WithdrawalData.Validated, WithdrawalRejection.RejectedInChecks, WithdrawalData.Checked] = {
    val doRunChecks: WIO[WithdrawalData.Validated, Nothing, WithdrawalData.Checked] =
      WIO.embed[WithdrawalData.Validated, Nothing, ChecksState.Decided, ChecksEngine.Context.Ctx, checksEmbedding.OutputState](
        checksEngine.runChecks
          .transformInput((x: WithdrawalData.Validated) => ChecksInput(x, service.getChecks())),
      )(checksEmbedding)

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
      .runIO[WithdrawalData.Checked](s =>
        service
          .initiateExecution(s.netAmount, s.recipient)
          .map(WithdrawalEvent.ExecutionInitiated.apply),
      )
      .handleEventWithError((s, event) =>
        event.response match {
          case ExecutionResponse.Accepted(externalId) => Right(s.executed(externalId))
          case ExecutionResponse.Rejected(error)      => Left(WithdrawalRejection.RejectedByExecutionEngine(error))
        },
      )
      .autoNamed()
      .retryIn(_ => WithdrawalWorkflow.executionRetryDelay)

  private def awaitExecutionCompletion: WIO[WithdrawalData.Executed, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .handleSignal(Signals.executionCompleted)
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
      .runIO[(WithdrawalData, WithdrawalRejection)]({ case (_, r) =>
        r match {
          case WithdrawalRejection.InvalidInput(error)              => WithdrawalEvent.RejectionHandled(error).pure[IO]
          case WithdrawalRejection.NotEnoughFunds()                 => WithdrawalEvent.RejectionHandled("Not enough funds on the user's account").pure[IO]
          case WithdrawalRejection.RejectedInChecks()               =>
            service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled("Transaction rejected in checks"))
          case WithdrawalRejection.RejectedByExecutionEngine(error) => service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled(error))
          case WithdrawalRejection.Cancelled(operatorId, comment)   =>
            service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled(s"Cancelled by ${operatorId}. Comment: ${comment}"))
        }
      })
      .handleEvent((_: (WithdrawalData, WithdrawalRejection), evt) => WithdrawalData.Completed.Failed(evt.error))
      .autoNamed()
  }

  private def handleCancellation = {
    WIO.interruption
      .throughSignal(Signals.cancel)
      .handleAsync((state, signal) => {
        def ok = WithdrawalEvent.WithdrawalCancelledByOperator(signal.operatorId, signal.comment).pure[IO]
        state match {
          case _: WithdrawalData.Empty     => ok
          case _: WithdrawalData.Initiated => ok
          case _: WithdrawalData.Validated => ok
          case _: WithdrawalData.Checking  => ok
          case _: WithdrawalData.Checked   => ok
          case _: WithdrawalData.Executed  =>
            if signal.acceptStartedExecution then ok
            else
              IO.raiseError(
                new Exception("To cancel transaction that has been already executed, this fact has to be explicitly accepted in the request."),
              )
          case _: WithdrawalData.Completed => IO.raiseError(new Exception(s"Unexpected state for cancellation: $state"))
        }
      })
      .handleEventWithError((_, evt) => WithdrawalRejection.Cancelled(evt.operatorId, evt.comment).asLeft)
      .voidResponse
      .done
  }

}
