# ProceedEvaluator Cast Refactor Plan

## Problem

`ProceedEvaluator.proceed` uses `asInstanceOf` casts due to Scala 3 match type limitations:
- `state.asInstanceOf[WCState[Ctx]]` 
- `_.wio.asInstanceOf[WIO.Initial[F, Ctx]]`

The match type `WorkflowContext.State[Ctx]` cannot be reduced when `Ctx` is abstract.

## Proposed Change (Option 3)

Change `Response` to carry full types and return `WFExecution` directly, moving the cast responsibility to the caller.

### Files to Modify

1. **workflows4s-core/src/main/scala/workflows4s/wio/internal/ProceedEvaluator.scala**
   - Change `Response` to carry full type parameters: `Response[F, Ctx, In, Err, Out]`
   - Return `Option[WFExecution[F, Ctx, In, Err, Out]]` instead of `Option[WIO.Initial[F, Ctx]]`
   - Remove the internal casts

2. **workflows4s-core/src/main/scala/workflows4s/wio/ActiveWorkflow.scala**
   - Update `effectlessProceed` to handle the new return type
   - Add the cast when extracting `WIO.Initial[F, Ctx]` from the result

### Trade-offs

**Pros:**
- Makes the cast explicit at the call site
- `ProceedEvaluator` becomes more type-safe internally
- Caller has full type information if needed

**Cons:**
- Cast doesn't disappear, just moves to `ActiveWorkflow`
- Slightly more verbose call site
- Fundamental limitation remains (match type can't reduce with abstract `Ctx`)

### Alternative Considered

Using type evidence `(using ev: In <:< WCState[Ctx])` would help with the input cast but not the output cast to `WIO.Initial`.

## Status

Deferred - low priority since the casts are safe at runtime and the change is cosmetic.
