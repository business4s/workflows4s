package workflows4s.runtime.instanceengine

import cats.Id
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class IdEffectTest extends AnyFreeSpec with EffectTestSuite[Id] with Matchers {

  given effect: Effect[Id] = Effect.idEffect

  // Id effect throws exceptions immediately rather than capturing them
  override def supportsLazyErrorCapture: Boolean = false

  "Id Effect" - {
    effectTests()
    wioRunIOTests()
  }
}
