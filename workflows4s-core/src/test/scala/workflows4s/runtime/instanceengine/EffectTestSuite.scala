package workflows4s.runtime.instanceengine

import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

trait EffectTestSuite[F[_]] extends AnyFreeSpecLike with Matchers {

  given effect: Effect[F]
  import Effect.{flatMap, map}

  val E: Effect[F] = effect

  /** Default implementation for testing failures. Runs the effect using runSyncUnsafe and returns the thrown exception. Can be overridden if a
    * specific effect type needs custom failure handling.
    */
  def assertFailsWith[A](fa: F[A]): Throwable = {
    import scala.util.{Failure, Success, Try}
    Try(effect.runSyncUnsafe(fa)) match {
      case Failure(e) => e
      case Success(_) => fail("Expected failure but got success")
    }
  }

  /** Common test suite for all Effect implementations. Contains 20 tests covering all Effect operations.
    */
  def effectTests(): Unit = {

    "pure" in {
      val program = E.pure(42)
      assert(effect.runSyncUnsafe(program) == 42)
    }

    "map" in {
      val program = E.map(E.pure(42))(_ * 2)
      assert(effect.runSyncUnsafe(program) == 84)
    }

    "flatMap" in {
      val program = E.flatMap(E.pure(42))(x => E.pure(x * 2))
      assert(effect.runSyncUnsafe(program) == 84)
    }

    "delay" in {
      var sideEffect = 0
      val program    = E.delay { sideEffect = 42; sideEffect }
      assert(sideEffect == 0) // Not executed yet (lazy)
      assert(effect.runSyncUnsafe(program) == 42)
      assert(sideEffect == 42)
    }

    "raiseError and handleErrorWith" in {
      val error   = new RuntimeException("test error")
      val program = E.handleErrorWith {
        E.raiseError[Int](error)
      } { _ =>
        E.pure(99)
      }
      assert(effect.runSyncUnsafe(program) == 99)
    }

    "Ref operations" - {
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

      "concurrent updates are atomic" in {
        val program = for {
          ref    <- E.ref(0)
          _      <- E.sequence((1 to 100).map(_ => ref.update(_ + 1)).toList)
          result <- ref.get
        } yield result

        assert(effect.runSyncUnsafe(program) == 100)
      }
    }

    "withLock" - {
      "executes effect while holding lock" in {
        val program = for {
          mutex  <- E.createMutex
          result <- {
            var executed = false
            E.withLock(mutex) {
              executed = true
              E.pure((42, executed))
            }
          }
        } yield result

        val (value, executed) = effect.runSyncUnsafe(program)
        assert(value == 42)
        assert(executed)
      }

      "releases lock on success" in {
        val program = for {
          mutex <- E.createMutex
          _     <- E.withLock(mutex)(E.pure(1))
          // Should be able to acquire again
          r     <- E.withLock(mutex)(E.pure(2))
        } yield r

        assert(effect.runSyncUnsafe(program) == 2)
      }

      "releases lock on failure" in {
        val error   = new RuntimeException("test")
        val program = for {
          mutex  <- E.createMutex
          failed <- E.attempt(E.withLock(mutex)(E.raiseError[Int](error)))
          // Should be able to acquire again after failure
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
          // Start multiple concurrent operations that each increment the counter
          _      <- E.sequence((1 to 10).map { _ =>
                      E.withLock(mutex) {
                        for {
                          current <- ref.get
                          // Small delay to increase chance of race condition without lock
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

    "start and join" in {
      val program = for {
        fiber   <- E.start(E.pure(42))
        outcome <- fiber.join
      } yield outcome

      effect.runSyncUnsafe(program) match {
        case Outcome.Succeeded(value) => assert(value == 42)
        case other                    => fail(s"Expected Succeeded, got $other")
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

    "traverse" in {
      val result = E.traverse(List(1, 2, 3))(x => E.pure(x * 2))
      assert(effect.runSyncUnsafe(result) == List(2, 4, 6))
    }

    "sequence" in {
      val result = E.sequence(List(E.pure(1), E.pure(2), E.pure(3)))
      assert(effect.runSyncUnsafe(result) == List(1, 2, 3))
    }

    "attempt" in {
      assert(effect.runSyncUnsafe(E.attempt(E.pure(42))) == Right(42))
      val error = new RuntimeException("test")
      assert(effect.runSyncUnsafe(E.attempt(E.raiseError[Int](error))) == Left(error))
    }

    "sleep" in {
      import scala.concurrent.duration.*
      val start   = System.currentTimeMillis()
      effect.runSyncUnsafe(E.sleep(50.millis))
      val elapsed = System.currentTimeMillis() - start
      assert(elapsed >= 50L)
    }
  }
}
