# Awaiting Signals

## Code

```scala file=./main/scala/workflow4s/example/docs/HandleSignalExample.scala start=start_withoutError end=end_withoutError
```

## BPMN

![handle-signal.svg](/../../workflows4s-example/src/test/resources/docs/handle-signal.svg)

## Model

```json file=./test/resources/docs/handle-signal.json
```

# Unhandled Signals

The Workflows4s API allows arbitrary signals to be sent to a workflow instance. While this provides flexibility, it also
requires developers to be disciplined and diligent, with thorough tests to ensure only valid signals are passed.

Technically, it is possible to constrain signals to a specific ADT, similar to how state and
events are managed. However, doing so would introduce significant complexity without fully eliminating unhandled
signals. By design, signals are only expected at specific moments in a workflow's lifecycle, meaning unhandled signals
can still occur outside those windows.