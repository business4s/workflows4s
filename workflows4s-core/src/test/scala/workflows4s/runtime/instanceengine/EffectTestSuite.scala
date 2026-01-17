package workflows4s.runtime.instanceengine

import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.internal.WakeupResult
import workflows4s.wio.{ActiveWorkflow, ErrorMeta, SignalDef, WCState, WIO, WorkflowContext}

import java.time.Instant

/** Test suite for Effect implementations. Tests mirror the required operations from EFFECT_REQUIREMENTS.md.
  */
trait EffectTestSuite[F[_]] extends AnyFreeSpecLike with Matchers {

  given effect: Effect[F]
  import Effect.{flatMap, map}

  val E: Effect[F] = effect

  // Generic test context for WIO tests - uses the effect type F from the test suite
  object WIOTestCtx extends WorkflowContext {
    trait Event
    case class SimpleEvent(value: String) extends Event
    type State = String

    type Eff[A] = F[A]
    given effect: Effect[Eff] = EffectTestSuite.this.effect

    extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
      def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Eff, Ctx] =
        ActiveWorkflow(WorkflowInstanceId("test", "test"), wio.provideInput(state), state)
    }

    def ignore[A, B, C]: (A, B) => C = (_, _) => ???

    given Conversion[String, SimpleEvent] = SimpleEvent.apply
  }

  def assertFailsWith[A](fa: F[A]): Throwable = {
    import scala.util.{Failure, Success, Try}
    Try(effect.runSyncUnsafe(fa)) match {
      case Failure(e) => e
      case Success(_) => fail("Expected failure but got success")
    }
  }

  def effectTests(): Unit = {

    // === Required Operations (see EFFECT_REQUIREMENTS.md) ===

    "pure" in {
      val program = E.pure(42)
      assert(effect.runSyncUnsafe(program) == 42)
    }

    "flatMap" in {
      val program = E.flatMap(E.pure(42))(x => E.pure(x * 2))
      assert(effect.runSyncUnsafe(program) == 84)
    }

    "map" in {
      val program = E.map(E.pure(42))(_ * 2)
      assert(effect.runSyncUnsafe(program) == 84)
    }

    "delay" in {
      var sideEffect = 0
      val program    = E.delay { sideEffect = 42; sideEffect }
      assert(sideEffect == 0) // Not executed yet (lazy)
      assert(effect.runSyncUnsafe(program) == 42)
      assert(sideEffect == 42)
    }

    "raiseError" in {
      val error = new RuntimeException("test error")
      assertFailsWith(E.raiseError[Int](error)) shouldBe error
    }

    "handleErrorWith" in {
      val error   = new RuntimeException("test error")
      val program = E.handleErrorWith(E.raiseError[Int](error))(_ => E.pure(99))
      assert(effect.runSyncUnsafe(program) == 99)
    }

    "ref" - {
      "get and set" in {
        val program = for {
          ref <- E.ref(0)
          v1  <- ref.get
          _   <- ref.set(42)
          v2  <- ref.get
        } yield (v1, v2)
        assert(effect.runSyncUnsafe(program) == (0, 42))
      }

      "update" in {
        val program = for {
          ref <- E.ref(10)
          _   <- ref.update(_ + 5)
          v   <- ref.get
        } yield v
        assert(effect.runSyncUnsafe(program) == 15)
      }

      "modify" in {
        val program = for {
          ref      <- E.ref(10)
          oldValue <- ref.modify(v => (v + 5, v))
          newValue <- ref.get
        } yield (oldValue, newValue)
        assert(effect.runSyncUnsafe(program) == (10, 15))
      }

      "getAndUpdate" in {
        val program = for {
          ref      <- E.ref(10)
          oldValue <- ref.getAndUpdate(_ + 5)
          newValue <- ref.get
        } yield (oldValue, newValue)
        assert(effect.runSyncUnsafe(program) == (10, 15))
      }

      "atomic updates" in {
        val program = for {
          ref    <- E.ref(0)
          _      <- E.sequence((1 to 100).map(_ => ref.update(_ + 1)).toList)
          result <- ref.get
        } yield result
        assert(effect.runSyncUnsafe(program) == 100)
      }
    }

    "sleep" in {
      import scala.concurrent.duration.*
      val start   = System.currentTimeMillis()
      effect.runSyncUnsafe(E.sleep(50.millis))
      val elapsed = System.currentTimeMillis() - start
      assert(elapsed >= 50L)
    }

    "createMutex and withLock" - {
      "executes effect while holding lock" in {
        val program = for {
          mutex  <- E.createMutex
          result <- E.withLock(mutex)(E.pure(42))
        } yield result
        assert(effect.runSyncUnsafe(program) == 42)
      }

      "releases lock on success" in {
        val program = for {
          mutex <- E.createMutex
          _     <- E.withLock(mutex)(E.pure(1))
          r     <- E.withLock(mutex)(E.pure(2))
        } yield r
        assert(effect.runSyncUnsafe(program) == 2)
      }

      "releases lock on failure" in {
        val error       = new RuntimeException("test")
        val program     = for {
          mutex  <- E.createMutex
          failed <- E.attempt(E.withLock(mutex)(E.raiseError[Int](error)))
          r      <- E.withLock(mutex)(E.pure(42))
        } yield (failed, r)
        val (failed, r) = effect.runSyncUnsafe(program)
        assert(failed == Left(error))
        assert(r == 42)
      }

      "ensures mutual exclusion" in {
        val program = for {
          mutex  <- E.createMutex
          ref    <- E.ref(0)
          _      <- E.sequence((1 to 10).map { _ =>
                      E.withLock(mutex) {
                        for {
                          current <- ref.get
                          _       <- E.delay(Thread.sleep(1))
                          _       <- ref.set(current + 1)
                        } yield ()
                      }
                    }.toList)
          result <- ref.get
        } yield result
        assert(effect.runSyncUnsafe(program) == 10)
      }
    }

    "start" - {
      "starts background computation" in {
        val program = for {
          fiber   <- E.start(E.pure(42))
          outcome <- fiber.join
        } yield outcome
        effect.runSyncUnsafe(program) match {
          case Outcome.Succeeded(value) => assert(value == 42)
          case other                    => fail(s"Expected Succeeded, got $other")
        }
      }

      "fiber.cancel marks as canceled" in {
        val program = for {
          fiber   <- E.start(E.sleep(scala.concurrent.duration.Duration(1, "s")))
          _       <- fiber.cancel
          outcome <- fiber.join
        } yield outcome
        effect.runSyncUnsafe(program) match {
          case Outcome.Canceled => succeed
          case other            => fail(s"Expected Canceled, got $other")
        }
      }
    }

    "guaranteeCase" - {
      "calls finalizer on success" in {
        var finalizerOutcome: Option[Outcome[Int]] = None
        val result                                 = E.guaranteeCase(E.pure(42)) { outcome =>
          finalizerOutcome = Some(outcome)
          E.pure(())
        }
        assert(effect.runSyncUnsafe(result) == 42)
        assert(finalizerOutcome == Some(Outcome.Succeeded(42)))
      }

      "calls finalizer on failure" in {
        val error                                  = new RuntimeException("test")
        var finalizerOutcome: Option[Outcome[Int]] = None
        val result                                 = E.guaranteeCase(E.raiseError[Int](error)) { outcome =>
          finalizerOutcome = Some(outcome)
          E.pure(())
        }
        assert(assertFailsWith(result) == error)
        assert(finalizerOutcome == Some(Outcome.Errored(error)))
      }
    }

    "runSyncUnsafe" in {
      assert(effect.runSyncUnsafe(E.pure(42)) == 42)
    }

    // === Derived Operations (have default implementations) ===

    "derived: traverse" in {
      val result = E.traverse(List(1, 2, 3))(x => E.pure(x * 2))
      assert(effect.runSyncUnsafe(result) == List(2, 4, 6))
    }

    "derived: sequence" in {
      val result = E.sequence(List(E.pure(1), E.pure(2), E.pure(3)))
      assert(effect.runSyncUnsafe(result) == List(1, 2, 3))
    }

    "derived: attempt" in {
      assert(effect.runSyncUnsafe(E.attempt(E.pure(42))) == Right(42))
      val error = new RuntimeException("test")
      assert(effect.runSyncUnsafe(E.attempt(E.raiseError[Int](error))) == Left(error))
    }
  }

  def wioRunIOTests(): Unit = {
    import WIOTestCtx.{SimpleEvent, ignore, toWorkflow, WIO as CtxWIO, given}

    "WIO.RunIO" - {

      "proceed" in {
        val wf = CtxWIO
          .runIO[String](input => E.pure(s"ProcessedEvent($input)"))
          .handleEvent(ignore)
          .done
          .toWorkflow("initialState")

        val resultOpt = wf.proceed(Instant.now)

        assert(resultOpt.toRaw.isDefined)
        val processingResult = E.runSyncUnsafe(resultOpt.toRaw.value)
        processingResult match {
          case WakeupResult.ProcessingResult.Proceeded(event) =>
            assert(event == SimpleEvent("ProcessedEvent(initialState)"))
          case _                                              => fail("Expected Proceeded result")
        }
      }

      "error in IO" in {
        val wf = CtxWIO
          .runIO[String](_ => E.raiseError(new RuntimeException("IO failed")))
          .handleEvent(ignore)
          .done
          .toWorkflow("initialState")

        val Some(result) = wf.proceed(Instant.now).toRaw: @unchecked

        val processingResult = E.runSyncUnsafe(result)
        processingResult match {
          case WakeupResult.ProcessingResult.Failed(ex) =>
            assert(ex.getMessage == "IO failed")

          case _ => fail("Expected Failed result")
        }
      }

      "event handling" in {
        val wf = CtxWIO
          .runIO[String](_ => ???)
          .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
          .done
          .toWorkflow("initialState")

        val Some(result) = wf.handleEvent("my-event"): @unchecked

        assert(result.staticState == "SuccessHandled(initialState, SimpleEvent(my-event))")
      }

      "handle signal" in {
        val wf = CtxWIO
          .runIO[Any](_ => ???)
          .handleEvent(ignore)
          .done
          .toWorkflow("initialState")

        val resultOpt = wf.handleSignal(SignalDef[String, String]())("").toRaw

        assert(resultOpt.isEmpty)
      }

      "metadata attachment" - {
        val base = CtxWIO
          .runIO[String](input => E.pure(s"EventGenerated($input)"))
          .handleEvent(ignore)

        extension (x: workflows4s.wio.WIO[?, ?, ?, ?, ?]) {
          def extractMeta: WIO.RunIO.Meta = x.asInstanceOf[WIO.RunIO[?, ?, ?, ?, ?, ?]].meta
        }

        "defaults" in {
          val wio = base.done

          val meta = wio.extractMeta
          assert(meta == WIO.RunIO.Meta(ErrorMeta.NoError(), None, None))
        }

        "explicitly named" in {
          val wio = base.named("ExplicitRunIO")

          val meta = wio.extractMeta
          assert(meta.name.contains("ExplicitRunIO"))
        }

        "autonamed" in {
          val autonamedRunIO = base.autoNamed()

          val meta = autonamedRunIO.extractMeta
          assert(meta.name.contains("Autonamed Run IO"))
        }

        "error autonamed" in {
          val wio = CtxWIO
            .runIO[String](_ => ???)
            .handleEventWithError((_, _) => Left(""))
            .done

          val meta = wio.extractMeta
          assert(meta.error == ErrorMeta.Present("String"))
        }

        "error explicitly named" in {
          val wio = CtxWIO
            .runIO[String](_ => ???)
            .handleEventWithError(ignore)(using ErrorMeta.Present("XXX"))
            .done

          val meta = wio.extractMeta
          assert(meta.error == ErrorMeta.Present("XXX"))
        }
      }
    }
  }
}
