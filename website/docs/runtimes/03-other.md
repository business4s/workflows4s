# Other

## In-Memory Runtime

In-memory runtime is built on top of cats-effect and allows to run a workflow without persistance. 
Its usage should typically be limited to tests or very exceptional scenarios.

## Unsafe In Memory Runtime

Alternative in-memory implementation that is not thread-safe and is build on vanilla scala.