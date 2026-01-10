package workflows4s.wio.builders

import workflows4s.wio.WorkflowContext

trait AllBuilders[F[_], Ctx <: WorkflowContext]
    extends WIOBuilderMethods[F, Ctx]
    with HandleSignalBuilderStep0[F, Ctx]
    with LoopBuilderStep0[F, Ctx]
    with AwaitBuilderStep0[F, Ctx]
    with ForkBuilderStep0[F, Ctx]
    with BranchBuilderStep0[F, Ctx]
    with DraftBuilderStep0[F, Ctx]
    with RunIOBuilderStep0[F, Ctx]
    with PureBuilderStep0[F, Ctx]
    with ParallelBuilderStep0[F, Ctx]
    with ForEachBuilderStep0[F, Ctx]
