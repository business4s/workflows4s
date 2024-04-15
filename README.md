# workflow4s

This repository contains an experiment to provide a bew way for achieveing durable execution in Scala.

It tries to merge Temporal's execution model with native event-sourcing while removing the most of the magic from the
solution.

See the [Example](workflow4s-example/src/main/scala/workflow4s/example) to see the end result.

This example is rendered
into bpmn ([withdrawal](workflow4s-example/src/test/resources/withdrawal-example-bpmn-declarative.bpmn),
[checks](workflow4s-example/src/test/resources/checks-engine.bpmn)) that can be opened in camunda modeler or
at [bpmn.io](http://bpmn.io).
The checks diagram is not layouted correctly but can be fixed by running `node ./auto-layout/autolayout.mjs`

## TODO

The following items are planned in scope of this PoC

- [x] Handling signals
- [x] Handling queries
- [x] Recovery from events
- [x] Handling step sequencing (flatMap)
- [x] Handling IO execution
- [x] Handling state transitions
- [x] Handling errors
- [x] Declarative API + graph rendering
- [x] Typesafe state queries
- [x] Handling interruptions
- [ ] Handling timers (await, timout)
- [ ] Checkpointing
- [ ] Splitting the workflow? (parallel execution and/or parallel waiting)
- [ ] Pekko/Shardcake/Postgres runtime PoC
- [ ] Test harness
- [ ] Explicit approach to handling workflow evolutions

### Followup work

Items below are outside of the scope of PoC but showcase the possibilities.

- [ ] UI & API to visualize workflow progress
- [ ] Observability support
- [ ] Non-interrupting events (e.g. signal or cycle timer)
  - non interrupting cycle timer example: every 24h send a notif

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
* workflow migrations (modify the workflow structure, e.g. order of operations) is a very complicated topic and will be
  described separately in due time

Internals:

* [WIO.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fwio%2FWIO.scala)[`WIO`](src/main/scala/workflow4s/wio/WIO.scala) - the
  basic building block and algebra defining the supported operations
* [ActiveWorkflow.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fwio%2FActiveWorkflow.scala) - the workflow state and
  entrypoint for interactions with the workflow
* [SimpleActor.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fwio%2Fsimple%2FSimpleActor.scala) - a very simple implementation
  of a mutable actor, until we have a proper, pekko-based, example
* [WithdrawalWorkflow.scala](src%2Fmain%2Fscala%2Fworkflow4s%2Fexample%2FWithdrawalWorkflow.scala) - an example of how
  to define a workflow
* [WithdrawalWorkflowTest.scala](src%2Ftest%2Fscala%2Fworkflow4s%2Fexample%2FWithdrawalWorkflowTest.scala) - a test
  intended to showcase actor usage and the general behaviour

# Alertnatives

Workflows4s is heavily inspired by Temporal, and other similar projects but it has two primary design differences:

* no additional server - there is no external component to be deployed and managed. Your code and database is all you
  need.
* process diagram rendering - it allows to render a process diagram from code

List of related projects:

| Project                                                                       | Self-contained | Code-first | Declarative |
|-------------------------------------------------------------------------------|----------------|------------|-------------|
| [Temporal](https://temporal.io/) & [Cadence](https://github.com/uber/cadence) | ❌              | ✅          | ❌           |
| [Camunda](https://camunda.com/)                                               | ❌              | ❌          | ✅           |
| [Conductor](https://github.com/Netflix/conductor)                             | ❌              | ❌          | ✅           |
| [Golem Cloud](https://www.golem.cloud/)                                       | ❌              | ✅          | ❌           |
| [Aws Step Functions](https://aws.amazon.com/step-functions/)                  | ❌              | ❌          | ✅           |
| [zio-flow](https://github.com/zio/zio-flow)                                   | ❌              | ✅          | ~ [2]       |
| [aecor](https://github.com/notxcain/aecor)                                    | ✅              | ✅          | ❌           |
| [endless](https://github.com/endless4s/endless)                               | ✅              | ✅          | ❌           |
| [infintic](infinitic.io)                                                      | ~ [1]          | ✅          | ❌           |
| [Baker](https://ing-bank.github.io/baker/)                                    | ✅              | ✅          | ✅           |

* [1] - Infintic requires Apache Pulsar, which can be seen as a database and is not specific to Infintic
* [2] - zio-flow could theoretically render the diagrams but its not implemented at the moment

A longer list of similar tools can be found [here](https://meirwah.github.io/awesome-workflow-engines/)