# workflow4s

This repository contains an experiment to provide a bew way for achieveing durable execution in Scala.

It tries to merge Temporal's execution model with native event-sourcing while removing the
most of the magic from the solution.

See the [Example](src/main/scala/workflow4s/example) to see the end result.




## TODO

The following items are planned in scope of this PoC

- [x] Handling signal
- [x] Handling queries
- [x] Recovery from events
- [x] Handling step sequencing (flatMap)
- [x] Handling IO execution
- [x] Handling state transitions
- [ ] Handling errors
- [ ] Handling postponed executions (await)
- [ ] Typesafe total queries? (currently state is filtered arbitraly by the query) 
- [ ] Splitting the workflow (parallel execution and/or parallel waiting)
- [ ] Pekko backend PoC
- [ ] Full example
- [ ] Test harness
- [ ] Explicit stance on handling workflow evolutions

## Design

### What it does?

* workflows are built using `WIO` monad
* a workflow supports following operations:
  * running side-effectful/non-deterministic computations
  * receiving signals that can modify the workflow state
  * querying the workflow state
  * recovering workflow state without re-triggering of side-effecting operations
* `WIO` is just a pure value object describing the workflow
  * to run it you need an interpreter with ability to persist events in a journal and read them

### How it works?

* on the first run
  * it executes IOs on its path. Each IO has to produce an event that is persisted in the journal.
  * event handlers are allowed to modify the workflow state
  * workflow stops when signal is expected and moves forward once signal is received
* during recovery (e.g. after service restart)
  * events are read from the journal and applied to the workflow
  * all IOs and signals are skipped if the corresponding event is registered
  * once events are exhausted the workflow continues to run as usual

Caveats:
* all the IOs need to be idempotent, we can't gaurantee exactly-once execution, only at-least-once
* workflow migrations (modify the workflow structure, e.g. order of operations) is a very complicated topic 
  and will be described separately in due time

Internals:
* [WIO.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fwio%2FWIO.scala)[`WIO`](src/main/scala/workflow4s/wio/WIO.scala) - the basic building block and algebra defining the supported operations
* [Interpreter.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fwio%2FInterpreter.scala) - the logic for handling particular operations
* [ActiveWorkflow.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fwio%2FActiveWorkflow.scala) - its the workflow state which also the interpretation result
* [SimpleActor.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fwio%2Fsimple%2FSimpleActor.scala) - a very simple implementation of a mutable actor, until we have a proper, pekko-based, example
* [WithdrawalWorkflow.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fexample%2FWithdrawalWorkflow.scala) - an example of how to define a workflow
* [WithdrawalWorkflowTest.scala](src%2Ftest%2Fscala%2Fworkflow4s%2Fexample%2FWithdrawalWorkflowTest.scala) - a test intended to showcase actor usage and the general behaviour