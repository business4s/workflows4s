# Document Fork onSome Plan

## Goal
Document the existing `onSome` method for N-way branching with:
1. Update Docusaurus docs
2. Create example app with mermaid rendering

---

## Files to Create/Modify

| File | Action |
|------|--------|
| `workflows4s-example/.../docs/MultiwayForkExample.scala` | **CREATE** - Example app |
| `workflows4s-example/src/test/.../docs/MultiwayForkExampleTest.scala` | **CREATE** - Test that generates mermaid |
| `website/docs/operations/06-fork.mdx` | **MODIFY** - Add N-way branching section |

---

## Part 1: Example App

### File: `workflows4s-example/src/main/scala/workflows4s/example/docs/MultiwayForkExample.scala`

```scala
package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

object MultiwayForkExample {

  case class OrderState(status: String, orderId: String)

  // start_multiway
  val processPending = WIO.pure(OrderState("processing", "123")).named("Process Payment")
  val shipOrder = WIO.pure(OrderState("shipped", "123")).named("Ship Order")
  val handleCancellation = WIO.pure(OrderState("cancelled", "123")).named("Handle Cancellation")
  val defaultAction = WIO.pure(OrderState("unknown", "123")).named("No Action")

  val multiwayFork: WIO[OrderState, Nothing, OrderState] =
    WIO
      .fork[OrderState]
      .onSome(s => Option.when(s.status == "pending")(s), "Pending?")(processPending)
      .onSome(s => Option.when(s.status == "paid")(s), "Paid?")(shipOrder)
      .onSome(s => Option.when(s.status == "cancelled")(s), "Cancelled?")(handleCancellation)
      .onSome(s => Some(s), "Default")(defaultAction)
      .named("Order Status Router")
      .done
  // end_multiway
}
```

---

## Part 2: Test for Mermaid Rendering

### File: `workflows4s-example/src/test/scala/workflows4s/example/docs/MultiwayForkExampleTest.scala`

```scala
package workflows4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.TestUtils

class MultiwayForkExampleTest extends AnyFreeSpec {

  "MultiwayForkExample" - {
    "render multiway fork" in {
      TestUtils.renderDocsExample(MultiwayForkExample.multiwayFork, "fork-multiway")
    }
  }
}
```

This will generate:
- `workflows4s-example/src/test/resources/docs/fork-multiway.json`
- `workflows4s-example/src/test/resources/docs/fork-multiway.bpmn`
- `workflows4s-example/src/test/resources/docs/fork-multiway.mermaid`

---

## Part 3: Update Documentation

### File: `website/docs/operations/06-fork.mdx`

```mdx
import OperationOutputs from '@site/src/components/OperationOutputs';

# Conditional Branching

Fork operations enable workflows to select a branch based on criteria.
It's equivalent to `if`/`match` instructions but allows for static rendering.

## Boolean Branching

For simple true/false conditions, use `matchCondition`:

```scala file=./main/scala/workflows4s/example/docs/ForkExample.scala start=start_doc end=end_doc
```

<OperationOutputs name="fork"/>

## Multi-Way Branching

For multiple conditions (similar to a `match` expression), use `onSome`. Each branch takes a function `In => Option[T]` - the first branch that returns `Some` is selected:

```scala file=./main/scala/workflows4s/example/docs/MultiwayForkExample.scala start=start_multiway end=end_multiway
```

<OperationOutputs name="fork-multiway"/>

**Key points:**
- Branches are evaluated in order - first matching branch wins
- Use `Option.when(condition)(value)` for simple boolean conditions
- Use `Some(value)` as the last branch for a catch-all default
- The function can transform the input type (e.g., extract optional data)

## Drafting Support

Branching comes with [drafting support](20-drafting.mdx).

```scala file=./main/scala/workflows4s/example/docs/draft/DraftForkExample.scala start=start_draft end=end_draft
```

<OperationOutputs name="draft-choice"/>
```

---

## Implementation Steps

1. Create `MultiwayForkExample.scala` example app
2. Create `MultiwayForkExampleTest.scala` test
3. Run test to generate mermaid/bpmn/json files: `sbt "workflows4s-example/testOnly *MultiwayForkExampleTest"`
4. Update `06-fork.mdx` with new documentation
5. Build website to verify: `cd website && yarn build`

---

## Expected Mermaid Output

The generated mermaid should show an exclusive gateway (diamond) with 4 outgoing branches:
- Pending? → Process Payment
- Paid? → Ship Order
- Cancelled? → Handle Cancellation
- Default → No Action

All branches converge back to a merge gateway.
