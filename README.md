# Workflows4s
![Discord](https://img.shields.io/discord/1240565362601230367?style=flat-square&logo=discord&link=https%3A%2F%2Fbit.ly%2Fbusiness4s-discord)

This repository contains an experimental approach to building workflows in Scala.

It tries to merge Temporal's execution model with native event-sourcing while removing the most of the magic from the
solution.

See the [**Docs**](https://business4s.github.io/workflows4s) for details.

## TODO

The following items are planned in the scope of this experiment

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
- [x] Handling timers (await, timout)
- [x] Pekko runtime PoC
- [ ] Postgres runtime PoC
- [ ] Checkpointing
- [ ] Test harness
- [ ] Explicit approach to handling workflow evolutions
- [ ] Persistent wake-ups
  - [ ] Quartz/db-scheduler integration?

### Followup work

Items below are outside the scope of PoC but showcase the possibilities.

- [ ] UI & API to visualize workflow progress
- [ ] Observability support
- [ ] Non-interrupting events (e.g. signal or cycle timer)
  - non interrupting cycle timer example: every 24h send a notif
- [ ] Splitting the workflow (parallel execution and/or parallel waiting)


## Design

### What does it do?

* workflows are built using `WIO` monad
* a workflow supports the following operations:
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

# Alternatives

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
