# In-Memory Runtimes

## In-Memory Runtime

The in-memory runtime is built on top of [`cats-effect`](https://typelevel.org/cats-effect/) and enables workflows to run without persistence. This runtime is ideal for:

- Testing workflows.
- Scenarios where workflows can be terminated or restarted from scratch without the need for state recovery.

### Example

Here is an example of using the in-memory runtime:

```scala file=./main/scala/workflow4s/example/docs/InMemoryRuntimeExample.scala start=async_doc_start end=async_doc_end
```

## Unsafe In-Memory Runtime

The unsafe in-memory runtime is an alternative implementation that:

- Is not thread-safe.
- Is built with vanilla Scala, making it lightweight and simple.

### Example

Here is an example of using the unsafe in-memory runtime:

```scala file=./main/scala/workflow4s/example/docs/InMemoryRuntimeExample.scala start=ssync_doc_start end=ssync_doc_end
