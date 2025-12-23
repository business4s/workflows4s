# Event Gateway Implementation Plan

## Goal
Add a BPMN-compliant Event Gateway primitive to workflows4s that waits for the **first** of N events (signals or timers) to occur, then executes that branch's continuation while discarding others.

## API Design (Parallel-inspired, Pre-composed branches)

```scala
// Define branches separately (reusable, testable)
val approvalBranch = WIO
  .handleSignal(ApproveSignal)
  .using[MyState]
  .purely((s, req) => Event.Approved(req.id))
  .handleEvent((s, evt) => s.copy(approved = true))
  .voidResponse
  .autoNamed >>> approvalContinuation

val rejectionBranch = WIO
  .handleSignal(RejectSignal)
  .using[MyState]
  .purely((s, req) => Event.Rejected(req.reason))
  .handleEvent((s, evt) => s.copy(rejected = true))
  .voidResponse
  .autoNamed >>> rejectionContinuation

val timeoutBranch = escalationTimer >>> escalationContinuation

// Compose into gateway
val gateway = WIO
  .eventGateway
  .taking[MyState]
  .withBranch(approvalBranch)
  .withBranch(rejectionBranch)
  .withBranch(timeoutBranch)
  .named("Approval Gateway")
  .done
```

**Rationale**: Pre-composed branches follow workflows4s composability philosophy. Branches are regular WIOs that can be defined, tested, and reused independently. The gateway detects branch type (signal or timer) by inspecting the first step.

## Branch Types

| Type | Behavior | Completion |
|------|----------|------------|
| Signal | Passive - waits for external delivery | When signal arrives |
| Timer | Semi-passive - starts clock | When timer releases |

After any branch wins, its **continuation** can be any WIO (including RunIO, Fork, etc.).

## Core Types

### 1. WIO.EventGateway (in WIO.scala)

```scala
case class EventGateway[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
    branches: Vector[EventGateway.Branch[F, Ctx, In, Err, Out]],
    status: EventGateway.Status,
    name: Option[String],
) extends WIO[F, In, Err, Out, Ctx]

object EventGateway {
  enum Status {
    case Pending                      // Waiting for any event
    case TimerStarted(branchIdx: Int) // A timer started, waiting for release
    case Selected(branchIdx: Int)     // Branch selected, executing continuation
  }

  case class Branch[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],  // The full branch (signal/timer >>> continuation)
      name: Option[String],
  )
}
```

### 2. Branch Detection

Each branch's WIO starts with either:
- `WIO.HandleSignal` - signal-based branch
- `WIO.Timer` - timer-based branch
- `WIO.AndThen` where first is signal/timer - with continuation

The evaluators will detect the branch type by pattern matching on the first WIO in each branch.

## Files to Modify

### Core Types
| File | Changes |
|------|---------|
| `workflows4s-core/.../wio/WIO.scala` | Add `EventGateway`, `EventGateway.Status`, `EventGateway.Branch` |
| `workflows4s-core/.../wio/Interpreter.scala` | Add `onEventGateway` to Visitor, add case in `run` |

### Evaluators (all in `workflows4s-core/.../wio/internal/`)
| File | Changes |
|------|---------|
| `SignalEvaluator.scala` | Try each branch for matching signal; first match wins |
| `GetWakeupEvaluator.scala` | Return earliest timer from pending branches |
| `GetSignalDefsEvaluator.scala` | Return all signal defs from pending branches |
| `ProceedEvaluator.scala` | When Selected, proceed into that branch |
| `EventEvaluator.scala` | Detect events, mark branch as selected |
| `RunIOEvaluator.scala` | Start timers, check for timer release |
| `GetStateEvaluator.scala` | Get state from selected branch |
| `GetIndexEvaluator.scala` | Get index from selected branch |
| `ExecutionProgressEvaluator.scala` | Build progress model for gateway |

### Builder
| File | Changes |
|------|---------|
| `workflows4s-core/.../builders/EventGatewayBuilder.scala` | **NEW** - Parallel-style builder |
| `workflows4s-core/.../builders/AllBuilders.scala` | Add `EventGatewayBuilderStep0` mixin |

### Model & BPMN
| File | Changes |
|------|---------|
| `workflows4s-core/.../wio/model/WIOModel.scala` | Add `EventGateway` model case |
| `workflows4s-core/.../wio/model/WIOMeta.scala` | Add `EventGateway` meta |
| `workflows4s-bpmn/.../BpmnRenderer.scala` | Render as BPMN event-based gateway |

## Evaluator Logic Summary

### SignalEvaluator.onEventGateway
```
status match {
  case Pending | TimerStarted(_) =>
    // Try each branch, first matching signal wins
    branches.zipWithIndex.collectFirstSome { (branch, idx) =>
      if branch starts with HandleSignal matching signalDef =>
        handle signal, return event, set status = Selected(idx)
    }
  case Selected(idx) =>
    // Delegate to selected branch
    recurse(branches(idx).wio)
}
```

### GetWakeupEvaluator.onEventGateway
```
status match {
  case Pending =>
    // Return earliest timer from ALL timer branches
    branches.flatMap(extractTimerWakeup).minOption
  case TimerStarted(idx) =>
    // Return wakeup from the started timer branch
    extractTimerWakeup(branches(idx))
  case Selected(idx) =>
    // Delegate to selected branch
    recurse(branches(idx).wio)
}
```

### EventEvaluator.onEventGateway
```
status match {
  case Pending =>
    branches.zipWithIndex.collectFirstSome { (branch, idx) =>
      if branch event matches =>
        if signal event: status = Selected(idx)
        if timer start event: status = TimerStarted(idx)
    }
  case TimerStarted(idx) =>
    if timer release event matches => status = Selected(idx)
  case Selected(idx) =>
    recurse(branches(idx).wio)
}
```

## Implementation Sequence

1. **Add core types** - `EventGateway` case class in WIO.scala
2. **Update Visitor** - Add abstract method and pattern match
3. **Implement evaluators** - One by one, starting with simpler ones
4. **Add builder** - EventGatewayBuilder following Parallel pattern
5. **Add model/meta** - For progress tracking
6. **Add BPMN rendering** - Event-based gateway XML
7. **Write tests** - Unit tests for each evaluator behavior

## Key Differences from Parallel

| Aspect | Parallel | EventGateway |
|--------|----------|--------------|
| Completion | Wait for ALL | Wait for FIRST |
| Result | Combined from all | From winning branch |
| Branches | Any WIO | Must start with signal/timer |
| Status | Track each element | Track which branch won |

## Validation

The builder should validate that each branch starts with either:
- `WIO.HandleSignal` - signal-based branch
- `WIO.Timer` - timer-based branch
- `WIO.AndThen` where first is one of the above

Invalid branches (e.g., `WIO.Pure`, `WIO.RunIO`, or `WIO.Fork` at the start) should produce a clear compile-time or runtime error.

## Sources
- [Camunda Event-Based Gateway Docs](https://docs.camunda.io/docs/components/modeler/bpmn/event-based-gateways/)
