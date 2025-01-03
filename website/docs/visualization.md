---
sidebar_position: 2.1
---

# Visualizations

Workflows4s provides two visualization modes for representing workflows: **BPMN** and **Mermaid Flowchart**. 
Each mode has its strengths and use cases.
Both options are considered experimental, and they are subject to change.

## Visualization Modes Overview

<table>
    <thead>
        <tr>
            <th>Mode</th>
            <th>Description</th>
            <th>Good For</th>
            <th>Limitations</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><code>BPMN</code></td>
            <td>
                Leverages the Business Process Model and Notation (BPMN) standard, widely recognized in workflow and process modeling domains. 
                Requires the <code>workflows4s-bpmn</code> module for conversion and rendering.
            </td>
            <td>
                <ul>
                    <li>Standardized format for workflow representation</li>
                    <li>Integration into BPMN-compatible tools</li>
                </ul>
            </td>
            <td>
                <ul>
                    <li>Basic auto-layout capabilities</li>
                    <li>Weaker integration ecosystem compared to modern alternatives</li>
                    <li>Additional dependency required: <code>workflows4s-bpmn</code></li>
                    <li>Rendering as image requires 3rd-party, community-provided tool</li>
                </ul>
            </td>
        </tr>
        <tr>
            <td><code>Mermaid Flowchart</code></td>
            <td>
                Renders workflows as Mermaid flowcharts using built-in support in <code>workflows4s-core</code>. 
                Provides more flexibility, relies on custom workflow representation.
            </td>
            <td>
                <ul>
                    <li>Better support within tools like GitLab, Markdown, and documentation platforms</li>
                    <li>Good auto-layout support</li>
                    <li>Rendering as image through official `mermaid-cli`</li>
                </ul>
            </td>
            <td>
                <ul>
                    <li>Non-standardized format</li>
                </ul>
            </td>
        </tr>
    </tbody>
</table>

---

## Usage Examples

### BPMN

```scala file=./main/scala/workflows4s/example/docs/visualization/BPMNExample.scala start=start_doc end=end_doc
```

![pull-request.svg](/../../workflows4s-example/src/test/resources/docs/pull-request.svg)

### Mermaid Flowchart


```scala file=./main/scala/workflows4s/example/docs/visualization/MermaidExample.scala start=start_doc end=end_doc
```

```mermaid file=./test/resources/docs/pull-request.mermaid
```
