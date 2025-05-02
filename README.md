# Workflows4s

![Discord](https://img.shields.io/discord/1240565362601230367?style=flat-square&logo=discord&link=https%3A%2F%2Fbit.ly%2Fbusiness4s-discord)
![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/org.business4s/workflows4s-core_3?server=https%3A%2F%2Foss.sonatype.org&style=flat-square)

**Workflows4s** is a library that helps in building long-running stateful processes sometimes called workflows. It tries
to merge Temporal's execution model, BMPN rendering and native event-sourcing while removing the most of the magic from the
solution.

See the [**Website**](https://business4s.github.io/workflows4s/) for details and join our [**Discord**](https://bit.ly/business4s-discord) for discussions.

## Visualizing Workflows

To visualize a workflow as a Mermaid diagram:

```scala
import workflows4s.mermaid.MermaidRenderer

// Visualize workflow definition
val flowchart = MermaidRenderer.renderWorkflow(workflow.toProgress)
println(flowchart.render)

// Visualize workflow execution progress
val progressFlowchart = MermaidRenderer.renderWorkflow(executedWorkflow.toProgress)
println(progressFlowchart.render)
```

### Viewing Rendered Diagrams

1. **Mermaid Live Editor**: Generate a URL to view the diagram in the Mermaid Live Editor
   ```scala
   val url = flowchart.toViewUrl
   println(s"View diagram at: $url")
   ```

2. **Markdown Integration**: Embed the Mermaid diagram in Markdown files
   ```markdown
   ```mermaid
   flowchart TD
   node0@{ shape: circle, label: "Start"}
   node1["@computation"]
   node0 --> node1
   ```
   ```