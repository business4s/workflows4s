package workflows4s.example.docs.wakeups

import cats.effect.{IO, ResourceIO}
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime

object FsKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.filesystem.FilesystemKnockerUpper

  given FilesystemKnockerUpper.StringCodec[MyWorkflowId] = ???
  val workDir: java.nio.file.Path                        = ???

  val knockerUpper = FilesystemKnockerUpper.create[MyWorkflowId](workDir)

  val runtime: WorkflowRuntime[IO, MyWorkflowCtx, MyWorkflowId] = createRuntime(knockerUpper)

  val process: ResourceIO[Unit] = knockerUpper.initialize(id => runtime.createInstance(id).flatMap(_.wakeup()))
  // docs_end

}
