# Wake-Ups

The `KnockerUpper` is responsible for waking up the workflow when needed, such as:

- When a workflow is awaiting a specific time to pass.
- When a workflow has timer-based interruptions.

This component ensures workflows resume execution at the right moment.

## Available Implementations

As of now, only non-persistent implementations are available:

- **`SleepingKnockerUpper`**: Relies on `IO.sleep`.
- **`PekkoKnockerUpper`**: Relies on the Pekko scheduler.
- **`NoOpKnockerUpper`**: Ignores registered wake-ups, effectively disabling this feature.

## Future Directions

Persistent implementations, such as those based on Quartz or db-scheduler, are possible and may be added in the future.
