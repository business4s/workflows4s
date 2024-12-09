# Persistance Without Event Sourcing

While workflows4s was designed to work through event-sourcing, where each change to the workflow is persisted through a
dedicated event, it's possible to run it in slightly different, possibly simpler, ways.

## Append-only state persistance

If you want to persist your state directly, and you're fine with doing it through append-only storage, it should work
just fine with
existing Workflows4s mechanisms.

```scala file=./main/scala/workflows4s/example/docs/AppendOnlyPersistance.scala start=start_example end=end_example
```

This should work but has not been battle-tested yet.

## Updatable state persistance

If you want to persist your state directly, and you apply your changes to the database directly (like in a standard
CRUD-based applications), it's theoretically possible to make it work if a checkpoint follows up every persistent action.

At the moment, checkpoints are not yet available, although they are on the short-term roadmap.
