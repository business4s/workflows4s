# Migrating Workflows

Migrating workflows is a key problem, and no workflow solutions can be considered complete without having an explicit
approach to it.

This is not yet done for Workflows4s, but the plan is to handle it through the mix of

1. Checkpointing - so parts of the workflows don't need to be replayed and can be freely changed
2. Journal rewrites - so the journal is able to handle new workflow shape
3. Branching - different logic based on the version of the workflow
4. Test harness - a dedicated support that can validate journal compatibility through some form of golden testing
4. Guides - explicit documentation on how to handle specific scenarios, like adding a step or removing it

All of that is not ready yet but will be provided before the first release. 