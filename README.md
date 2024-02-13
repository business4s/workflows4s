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
- [ ] Handling state transitions
- [ ] Handling errors
- [ ] Handling posponed executions (await)
- [ ] Pekko backend PoC
- [ ] Full example
