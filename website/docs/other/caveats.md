# Caveats

Workflows4s assumes full idempotency of the logic executed within the workflow.
This is required because in case of any failure, Workflows4s will retry the step ad-infinitum.

This also means it's technically possible for two branches to be executed, while only one leading to the next step.
Imagine the following scenario

<!-- TODO image -->

1. Task gets started and executed
2. A network issue occurs and event cannot be persisted
3. Interruption timer triggers and workflow successfully continues through the interruption path.

This way the task got executed, but it won't be ever recorded in the workflow state (events).
In case this is an issue, user is expected to incorporate distributed lock into the task/timer handling logic. 
Alternatively, the interruption should not encompass the task. 

If the problem is frequent enough, the pre-execution event can be added as an option in Workflows4s,
but this can be currently emulated through workflow definition without the direct support. 