---
sidebar_position: 1.1
sidebar_label: Workflows4s Anatomy
---

# Workflows4s Anatomy

## What Does It Do?

Workflows4s enables the creation of workflows using the `WIO` data type. A workflow built with `WIO` supports the
following operations, as through the [Workflow Elements](../category/workflow-elements):

- **Running side-effectful computations**
- **Receiving signals** from the external world
- **Querying workflow state** at any time
- **Recovering workflow state** without re-triggering side-effecting operations

`WIO` is a pure value object that describes the workflow. However, to execute it, you need a [runtime](../runtimes)
capable of:

- Persisting events in a journal
- Reading events from the journal

```mermaid
flowchart LR
node0@{ shape: "rect", label: "Your logic"}
node1@{ shape: "rect", label: "WIO (Workflow Definition)"}
node1_1@{ shape: "rect", label: "Runtime Dependencies"}
node2@{ shape: "rect", label: "WorkflowRuntime"}
node3@{ shape: "rect", label: "WorkflowInstance"}
node4@{ shape: "stadium", label: "createInstance"}
node4_1@{ shape: "rect", label: "Workflow Instance ID"}
node4_2@{ shape: "rect", label: "Events"}
node5@{ shape: "stadium", label: "queryState"}
node6@{ shape: "stadium", label: "wakeup"}
node7@{ shape: "stadium", label: "deliverSignal"}
node8@{ shape: "rect", label: "Renderer"}
node9@{ shape: "rect", label: "Static Visualization"}
node10@{ shape: "rect", label: "Progress Visualization"}


node0 --> node1
node1 --> node2
node1_1 --> node2
node2 --> node4
node4_1 --> node4
node4 --> node3
node4_2 -.-> node3
node3 --> node5
node3 --> node6
node3 --> node7

node6 -.-> node4_2
node7 -.-> node4_2

node3 --> node10
node8 --> node10

node1 --> node9
node8 --> node9


```

## How Does It Work?

### First Run

1. The workflow executes `IO`s along its path. Each `IO` produces an event that is persisted in the journal.
2. Event handlers can modify the workflow state based on the events.
3. The workflow halts execution when a signal or a timer is expected and resumes once the conditions are met.

### Recovery (e.g., After a Service Restart)

1. Events are read from the journal and applied to the workflow.
2. All previously executed `IO`s and received signals are skipped if corresponding events are already registered.
3. Once all events are processed, the workflow continues execution as normal.
