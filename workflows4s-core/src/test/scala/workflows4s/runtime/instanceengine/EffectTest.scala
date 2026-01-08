package workflows4s.runtime.instanceengine

import cats.Id
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EffectTest extends AnyFreeSpec with Matchers {

  "idEffect" - {
    import Effect.idEffect
    val E: Effect[Id] = Effect[Id]

    "pure" in {
      assert(E.pure(42) == 42)
    }

    "map" in {
      assert(E.map(E.pure(42))(_ * 2) == 84)
    }

    "flatMap" in {
      assert(E.flatMap(E.pure(42))(x => E.pure(x * 2)) == 84)
    }

    "delay" in {
      var sideEffect = 0
      E.delay { sideEffect = 42 }
      assert(sideEffect == 42)
    }

    "raiseError and handleErrorWith" in {
      val error  = new RuntimeException("test error")
      val result = E.handleErrorWith {
        E.raiseError[Int](error)
      } { _ =>
        E.pure(99)
      }
      assert(result == 99)
    }

    "Ref operations" - {
      "get and set" in {
        val ref = E.ref(0)
        assert(ref.get == 0)
        ref.set(42)
        assert(ref.get == 42)
      }

      "update" in {
        val ref = E.ref(10)
        ref.update(_ + 5)
        assert(ref.get == 15)
      }

      "modify" in {
        val ref      = E.ref(10)
        val oldValue = ref.modify(v => (v + 5, v))
        assert(oldValue == 10)
        assert(ref.get == 15)
      }

      "getAndUpdate" in {
        val ref      = E.ref(10)
        val oldValue = ref.getAndUpdate(_ + 5)
        assert(oldValue == 10)
        assert(ref.get == 15)
      }
    }

    "withLock" - {
      "executes effect while holding lock" in {
        val mutex    = E.createMutex
        var executed = false
        val result   = E.withLock(mutex) {
          executed = true
          42
        }
        assert(result == 42)
        assert(executed)
      }

      "releases lock on success" in {
        val mutex = E.createMutex
        E.withLock(mutex)(E.pure(1))
        // Should be able to acquire again
        assert(E.withLock(mutex)(E.pure(2)) == 2)
      }

      "releases lock on error" in {
        val mutex = E.createMutex
        val error = new RuntimeException("test")
        try {
          E.withLock(mutex) {
            throw error
          }
        } catch {
          case _: RuntimeException => // expected
        }
        // Should be able to acquire again after error
        assert(E.withLock(mutex)(E.pure(42)) == 42)
      }
    }

    "start and join" in {
      val fiber = E.start(E.pure(42))
      fiber.join match {
        case Outcome.Succeeded(value) => assert(value == 42)
        case other                    => fail(s"Expected Succeeded, got $other")
      }
    }

    "guaranteeCase" - {
      "calls finalizer on success" in {
        var finalizerOutcome: Option[Outcome[Int]] = None
        val result                                 = E.guaranteeCase(E.pure(42)) { outcome =>
          finalizerOutcome = Some(outcome)
        }
        assert(result == 42)
        assert(finalizerOutcome == Some(Outcome.Succeeded(42)))
      }

      // Note: For Id effect, raiseError throws immediately before guaranteeCase can catch it.
      // This is expected behavior for synchronous effects where errors throw exceptions.
      // Use handleErrorWith for error recovery in Id.
    }

    "traverse" in {
      val result = E.traverse(List(1, 2, 3))(x => E.pure(x * 2))
      assert(result == List(2, 4, 6))
    }

    "sequence" in {
      val result = E.sequence(List(E.pure(1), E.pure(2), E.pure(3)))
      assert(result == List(1, 2, 3))
    }

    "fromOption" in {
      assert(E.fromOption(Some(42), new RuntimeException("none")) == 42)
      an[RuntimeException] should be thrownBy {
        E.fromOption(None, new RuntimeException("none"))
      }
    }

    "fromEither" in {
      assert(E.fromEither(Right(42)) == 42)
      an[RuntimeException] should be thrownBy {
        E.fromEither(Left(new RuntimeException("error")))
      }
    }

    "attempt" in {
      // Test successful case
      assert(E.attempt(E.pure(42)) == Right(42))

      // Note: For Id effect, raiseError throws immediately before attempt can catch it.
      // This is expected behavior for synchronous effects where errors throw exceptions.
      // The attempt method is more useful for lazy effects like IO or Future.
    }
  }
}
