package workflows4s.wio.builders

import workflows4s.wio.WorkflowContext

trait AllBuilders[F[_], Ctx <: WorkflowContext]
    extends WIOBuilderMethods[F, Ctx]
    with HandleSignalBuilder.Step0[F, Ctx]
    with InterruptionBuilder.Step0[F, Ctx]
    with LoopBuilder.Step0[F, Ctx]
    with AwaitBuilder.Step0[F, Ctx]
    with ForkBuilder.Step0[F, Ctx]
    with BranchBuilder.Step0[F, Ctx]
    with DraftBuilder.Step0[Ctx]
    with RunIOBuilder.Step0[F, Ctx]
    with PureBuilder.Step0[F, Ctx]
    with ParallelBuilder.Step0[F, Ctx]
    with ForEachBuilder.Step0[F, Ctx]
