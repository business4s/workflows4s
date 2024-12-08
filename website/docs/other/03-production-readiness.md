# Production Readiness

Workflows4s began as a Proof of Concept to validate the approach and assess its technical feasibility. The initial
stages have demonstrated promising results, confirming the viability of this approach.
Now, we’re committed to making it production-grade, which might take some time.

The library is still under active development, and we’re working hard to make it more stable, add all the mandatory
features, and polish everything to production quality.

We’re aiming for an early release in Q1 2025.
Your feedback and ideas are a big part of making Workflows4s a solid and reliable tool.
If you're interested in using it,
please join our [Discord](https://bit.ly/business4s-discord) to share your use-case and discuss current
status of the project.

## Roadmap

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
- [x] Handling timers (await, timeout)
- [x] Pekko runtime PoC
- [x] Postgres runtime PoC
- [ ] Checkpointing
- [ ] Test harness
- [ ] Explicit approach to handling workflow evolutions
- [ ] Persistent wake-ups - Quartz/db-scheduler integration

### Follow-up Work

- [ ] UI & API to visualize workflow progress
- [ ] Observability support
- [ ] Non-interrupting events (e.g., signal or cycle timer)
    - Example: every 24 hours, send a notification without interrupting the workflow
- [ ] Splitting the workflow (parallel execution and/or parallel waiting)