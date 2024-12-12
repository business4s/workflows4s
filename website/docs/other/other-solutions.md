# Other Solutions

Workflows4s is heavily inspired by Temporal and other similar projects, but it stands out with two primary design
differences:

- **No Additional Server**: There’s no external component to deploy or manage. Your code and database are all you need.
- **Process Diagram Rendering**: Workflows4s allows you to render process diagrams directly from code.

## Comparison of Related Projects

Below is a comparison of Workflows4s with other workflow orchestration tools based on whether they are self-contained,
code-first, and declarative:

| Project                                                                       | Self-contained | Code-first | Declarative |
|-------------------------------------------------------------------------------|----------------|------------|-------------|
| [Temporal](https://temporal.io/) & [Cadence](https://github.com/uber/cadence) | ❌              | ✅          | ❌           |
| [Camunda](https://camunda.com/)                                               | ❌              | ❌          | ✅           |
| [Conductor](https://github.com/Netflix/conductor)                             | ❌              | ❌          | ✅           |
| [Golem Cloud](https://www.golem.cloud/)                                       | ❌              | ✅          | ❌           |
| [AWS Step Functions](https://aws.amazon.com/step-functions/)                  | ❌              | ❌          | ✅           |
| [zio-flow](https://github.com/zio/zio-flow)                                   | ❌              | ✅          | ~           |
| [aecor](https://github.com/notxcain/aecor)                                    | ✅              | ✅          | ❌           |
| [endless](https://github.com/endless4s/endless)                               | ✅              | ✅          | ❌           |
| [infintic](https://infinitic.io)                                              | ~              | ✅          | ❌           |
| [Baker](https://ing-bank.github.io/baker/)                                    | ✅              | ✅          | ✅           |

### Notes:

1. **infintic**: Requires Apache Pulsar, which can be seen as a database and is not specific to infintic.
2. **zio-flow**: Could theoretically render diagrams, but this feature has not been implemented.

A longer list of similar tools can be found [here](https://meirwah.github.io/awesome-workflow-engines/).

## Explanation of Terms

### Self-contained

A solution is considered self-contained if it doesn’t require additional external servers or components. All
functionality is encapsulated within your application and existing infrastructure, such as your database.

### Code-first

A code-first approach means workflows are defined programmatically using standard programming constructs instead of
external DSLs, GUIs, or configuration files. This approach integrates naturally with your existing codebase.

### Declarative

A declarative workflow means the shape of the workflow is static and can be rendered as a diagram or model. This is
different from dynamic workflows, like those in Temporal, where the structure evolves based on runtime logic defined in
code.