# In-Memory Runtimes

Both in-memory runtimes store workflow state in memory without persistence. They differ in their concurrency model:

| | Synchronized | Concurrent |
|---|---|---|
| **Module** | `workflows4s-core` | `workflows4s-cats-effect` |
| **Effect requirements** | `MonadThrow + WeakSync` | `Async` (cats-effect) |
| **Under contention** | Blocks thread | Suspends fiber |
| **On cancellation** | Lock may leak | Releases lock cleanly |
| **Best for** | Tests, simple/synchronous usage | Concurrent/async usage with fibers |

## Concurrent Runtime

The concurrent runtime is built on top of [`cats-effect`](https://typelevel.org/cats-effect/) and uses functional concurrency primitives (Ref, Semaphore). It is fiber-safe and cancellation-safe.

### Example

```scala file=./main/scala/workflows4s/example/docs/InMemoryConcurrentRuntimeExample.scala start=concurrent_doc_start end=concurrent_doc_end
```

## Synchronized Runtime

The synchronized runtime uses JVM-level synchronization primitives. It is thread-safe but not fiber-safe — under contention it blocks the carrier thread rather than suspending the fiber.

### Example

```scala file=./main/scala/workflows4s/example/docs/InMemorySynchronizedRuntimeExample.scala start=synchronized_doc_start end=synchronized_doc_end
```
