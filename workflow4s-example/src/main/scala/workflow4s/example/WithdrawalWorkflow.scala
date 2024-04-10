package workflow4s.example

import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxEitherId}
import workflow4s.example.WithdrawalEvent.{MoneyLocked, WithdrawalAccepted, WithdrawalRejected}
import workflow4s.example.WithdrawalService.ExecutionResponse
import workflow4s.example.WithdrawalSignal.{CancelWithdrawal, CreateWithdrawal, ExecutionCompleted}
import workflow4s.example.WithdrawalWorkflow.{Context, Signals, checksEmbedding}
import workflow4s.example.checks.*
import workflow4s.wio
import workflow4s.wio.internal.WorkflowConversionEvaluator.WorkflowEmbedding
import workflow4s.wio.{SignalDef, WCState, WorkflowContext}

object WithdrawalWorkflow {

  object Context extends WorkflowContext {
    override type Event = WithdrawalEvent
    override type State = WithdrawalData
  }

  object Signals {
    val createWithdrawal   = SignalDef[CreateWithdrawal, Unit]()
    val executionCompleted = SignalDef[ExecutionCompleted, Unit]()
    val cancel             = SignalDef[CancelWithdrawal, Unit]()
  }

  val checksEmbedding = new WorkflowEmbedding[ChecksEngine.Context.type, WithdrawalWorkflow.Context.type, WithdrawalData.Validated] {
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
      case x: WithdrawalData.Checking => Some(x.checkResults)
      case x: WithdrawalData.Checked  => Some(x.checkResults)
      case _                          => None
    }
  }

}

class WithdrawalWorkflow(service: WithdrawalService, checksEngine: ChecksEngine) {

  import WithdrawalWorkflow.Context.WIO

  val workflow: WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
    (for {
      _ <- receiveWithdrawal
      _ <- calculateFees
      _ <- putMoneyOnHold
      _ <- runChecks
      _ <- execute
      s <- releaseFunds
    } yield s)
      .handleErrorWith(cancelFundsIfNeeded)

  val workflowDeclarative: WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
    (
      (
        receiveWithdrawal >>>
          calculateFees >>>
          putMoneyOnHold >>>
          runChecks >>>
          execute
      ).interruptWith(handleCancellation)
        >>> releaseFunds
    ).handleErrorWith(cancelFundsIfNeeded) >>> WIO.noop()

  private def receiveWithdrawal: WIO[WithdrawalData.Empty, WithdrawalRejection.InvalidInput, WithdrawalData.Initiated] =
    WIO
      .handleSignal[WithdrawalData.Empty](Signals.createWithdrawal) { (_, signal) =>
        IO {
          if (signal.amount > 0) WithdrawalAccepted(signal.amount, signal.recipient)
          else WithdrawalRejected("Amount must be positive")
        }
      }
      .handleEventWithError { (st, event) =>
        event match {
          case WithdrawalAccepted(amount, recipient) => st.initiated(amount, recipient).asRight
          case WithdrawalRejected(error)             => WithdrawalRejection.InvalidInput(error).asLeft
        }
      }
      .produceResponse((_, _) => ())
      .autoNamed()

  private def calculateFees: WIO[WithdrawalData.Initiated, Nothing, WithdrawalData.Validated] = WIO
    .runIO[WithdrawalData.Initiated](state => service.calculateFees(state.amount).map(WithdrawalEvent.FeeSet.apply))
    .handleEvent { (state, event) => state.validated(event.fee) }
    .autoNamed()

  private def putMoneyOnHold: WIO[WithdrawalData.Validated, WithdrawalRejection.NotEnoughFunds, WithdrawalData.Validated] =
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
      WIO.embed[WithdrawalData.Validated, Nothing, ChecksState.Decided, ChecksEngine.Context.type, checksEmbedding.OutputState](
        checksEngine.runChecks
          .transformInput((x: WithdrawalData.Validated) => ChecksInput(x, List())),
          //          .transformOutput((validated, checkState) => validated.checked(checkState)),
      )(checksEmbedding, _ => ChecksState.Empty) // TODO

    val actOnDecision = WIO
      .pure[WithdrawalData.Checked]
      .makeError(_.checkResults.decision match {
        case Decision.RejectedBySystem()   => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedBySystem()   => None
        case Decision.RejectedByOperator() => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedByOperator() => None
      })

    doRunChecks >>> actOnDecision
  }

  // TODO can fail with provider fatal failure, need retries, needs cancellation
  private def execute: WIO[WithdrawalData.Checked, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    initiateExecution >>> awaitExecutionCompletion

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

  private def awaitExecutionCompletion: WIO[WithdrawalData.Executed, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .handleSignal[WithdrawalData.Executed](Signals.executionCompleted)((_, sig) => IO(WithdrawalEvent.ExecutionCompleted(sig)))
      .handleEventWithError((s, e: WithdrawalEvent.ExecutionCompleted) =>
        e.status match {
          case ExecutionCompleted.Succeeded => Right(s)
          case ExecutionCompleted.Failed    => Left(WithdrawalRejection.RejectedByExecutionEngine("Execution failed"))
        },
      )
      .produceResponse((_, _) => ())
      .autoNamed()

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

  private def handleCancellation: WIO.Interruption[WithdrawalRejection.Cancelled, Nothing] = {
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
            if (signal.acceptStartedExecution) ok
            else
              IO.raiseError(
                new Exception("To cancel transaction that has been already executed, this fact has to be explicitly accepted in the request."),
              )
          case _: WithdrawalData.Completed => IO.raiseError(new Exception(s"Unexpected state for cancellation: $state"))
        }
      })
      .handleEventWithError((_, evt) => WithdrawalRejection.Cancelled(evt.operatorId, evt.comment).asLeft)
      .voidResponse
      .noFollowupSteps
      .done
  }

}
