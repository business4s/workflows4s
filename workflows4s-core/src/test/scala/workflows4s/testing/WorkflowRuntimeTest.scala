package workflows4s.testing

import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.runtime.instanceengine.{Effect, Outcome}
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.wio.{SignalDef, WIO, WorkflowContext}

import scala.compiletime.deferred
import scala.concurrent.duration.*
import scala.reflect.ClassTag

// --- 1. Generic Context Definition ---

object GenericTestCtx {
  case class State(steps: List[String])
  object State {
    def empty: State = State(List.empty)
  }

  case class Event(stepId: String)
}

trait GenericTestCtx[F[_]](using F: Effect[F]) extends WorkflowContext {
  override type State  = GenericTestCtx.State
  override type Event  = GenericTestCtx.Event
  override type Eff[A] = F[A]
  override given effect: Effect[Eff] = F
}

// --- 2. The Test Suite (With Inner Utils) ---

trait WorkflowRuntimeTest[F[_]] extends AnyFreeSpecLike {

  given effect: Effect[F]         = deferred
  def unsafeRun(program: => F[Unit]): Unit
  def testTimeout: FiniteDuration = 5.seconds

  // --- Context Initialization ---
  // The Single Source of Truth for the Context
  val ctx: GenericTestCtx[F] = new GenericTestCtx[F]() {}
  import GenericTestCtx.State

  type Adapter = WorkflowTestAdapter[F, ctx.type]

  // --- Helper Classes ---
  class TestLatch(ref: workflows4s.runtime.instanceengine.Ref[F, Boolean]) {
    def release: F[Unit] = ref.set(true)
    def acquire: F[Unit] = {
      def loop: F[Unit] = ref.get.flatMap {
        case true  => effect.unit
        case false => effect.sleep(10.millis) *> loop
      }
      loop
    }
  }
  object TestLatch                                                         {
    def create: F[TestLatch] = effect.ref(false).map(new TestLatch(_))
  }

  // --- Internal Test Utilities ---
  // Defined INSIDE the trait to share the exact same 'ctx' instance
  class TestUtils(using E: Effect[F]) {

    import GenericTestCtx.{Event, State}

    // Aliases using the Outer 'ctx'
    // Note: We cast ctx.Eff back to F explicitly where needed,
    // but the Types rely on ctx.type
    type TestWIO[Err]          = workflows4s.wio.WIO[F, State, Err, State, ctx.type]
    type TestInterruption[Err] = workflows4s.wio.WIO.Interruption[F, ctx.type, Err, State]

    def runCustom(fa: => F[Unit]): (String, TestWIO[Nothing]) = {
      val id   = java.util.UUID.randomUUID().toString
      val step = ctx.WIO
        .runIO[State]
        .apply(_ => E.map(fa)(_ => Event(id)))
        .handleEvent((state, _) => state)
        .named(id)
      (id, step.asInstanceOf[TestWIO[Nothing]])
    }

    def signalInterruption[S: ClassTag](onDeliver: => F[Unit]): (SignalDef[S, Unit], TestInterruption[Nothing]) = {
      val id        = java.util.UUID.randomUUID().toString
      val signalDef = SignalDef[S, Unit](id)

      val interruption = ctx.WIO.interruption
        .throughSignal(signalDef)
        .handleAsync((_, _) => E.map(onDeliver)(_ => Event(id)))
        .handleEvent((state, _) => state)
        .voidResponse
        .named(id)

      (signalDef, interruption.asInstanceOf[TestInterruption[Nothing]])
    }
  }

  // --- Test Logic ---

  def workflowTests(getAdapter: => Adapter): Unit = {

    "runtime should not allow interrupting a process while another step is running" in {
      val runtime = getAdapter
      val utils   = new TestUtils // Instantiate inner class

      def createInstance(wio: WIO[F, State, ?, ?, ctx.type]): runtime.Actor = {
        // Safe cast to Initial WIO (No Error, State Output)
        val specificWio = wio.asInstanceOf[WIO[F, State, Nothing, State, ctx.type]]
        val initialWio  = specificWio.provideInput(State.empty)
        runtime.runWorkflow(initialWio, State.empty)
      }

      def singleRun(i: Int): F[Unit] = {
        effect.delay(println(s"Running $i iteration")) *> {
          for {
            longRunningStarted  <- TestLatch.create
            longRunningFinished <- TestLatch.create
            signalStartedRef    <- effect.ref(false)

            (longRunningStepId, longRunningStep) = utils.runCustom(
                                                     longRunningStarted.release *> longRunningFinished.acquire,
                                                   )

            (signal, interruption) = utils.signalInterruption[Int](signalStartedRef.set(true))

            // Everything shares 'ctx', so types match perfectly
            wio = longRunningStep.interruptWith(interruption)

            wf = createInstance(wio)

            wakeupFiber <- effect.start(wf.wakeup())

            signalFiber <- effect.start(
                             longRunningStarted.acquire *> wf.deliverSignal(signal, 1),
                           )

            initialState <- wf.queryState()
            _             = assert(initialState == State.empty)

            _ <- longRunningFinished.release

            _ <- wakeupFiber.join.flatMap {
                   case Outcome.Succeeded(_) => effect.unit
                   case _                    => effect.raiseError(new Exception("Wakeup failed"))
                 }

            stateAfterWakeup <- wf.queryState()
            _                 = assert(stateAfterWakeup == State.empty)

            signalStarted <- signalStartedRef.get
            _             <- effect.delay(assert(!signalStarted, "Signal should not have executed"))

            finalState <- wf.queryState()
            _           = assert(finalState == State.empty)
          } yield ()
        }
      }

      val testProgram = effect.traverse_((1 to 50).toList)(singleRun)
      unsafeRun(testProgram)
    }
  }
}
