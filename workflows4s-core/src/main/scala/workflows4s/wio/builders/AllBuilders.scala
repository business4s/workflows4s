package workflows4s.wio.builders

import workflows4s.wio.WorkflowContext

trait AllBuilders[Ctx <: WorkflowContext]
    extends WIOBuilderMethods[Ctx]
    with HandleSignalBuilder.Step0[Ctx]
    with LoopBuilder.Step0[Ctx]
    with AwaitBuilder.Step0[Ctx]
    with ForkBuilder.Step0[Ctx]
    with BranchBuilder.Step0[Ctx]
    with DraftBuilder.Step0[Ctx]
    with RunIOBuilder.Step0[Ctx]
    with PureBuilder.Step0[Ctx]
    with ParallelBuilder.Step0[Ctx]
