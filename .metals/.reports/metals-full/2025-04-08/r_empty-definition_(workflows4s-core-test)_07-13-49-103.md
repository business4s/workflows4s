error id: `<none>`.
file:///C:/Users/jaisw/workflows4s/workflows4s-core/src/test/scala/workflows4s/wio/WIOSpec.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 313
uri: file:///C:/Users/jaisw/workflows4s/workflows4s-core/src/test/scala/workflows4s/wio/WIOSpec.scala
text:
```scala
package workflows4s.wio

import weaver.SimpleIOSuite
import workflows4s.wio.WIO.Pure
import workflows4s.wio.WIO

object WIOSpec extends SimpleIOSuite {

  // Dummy workflow context and state to satisfy type constraints
  trait DummyCtx extends WorkflowContext
  case class DummyState(value: String) exte@@nds WCState[DummyCtx]

  test("WIO.Pure returns expected Right output") {
    val expected = DummyState("ok")

    val pure = Pure[DummyCtx, Any, Nothing, DummyState](
      _ => Right(expected),
      meta = Pure.Meta(ErrorMeta.none, Some("testPure"))
    )

    val result = pure.value(())
    expect(result == Right(expected))
  }

  test("WIO.Pure can return an error with Left") {
    sealed trait MyError
    case object Fail extends MyError

    val pure = Pure[DummyCtx, Any, MyError, DummyState](
      _ => Left(Fail),
      meta = Pure.Meta(ErrorMeta.none, Some("failPure"))
    )

    val result = pure.value(())
    expect(result == Left(Fail))
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.