package workflows4s.example.docs.wakeups

import cats.effect.{IO, ResourceIO}
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime

object FsKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.filesystem.FilesystemKnockerUpper

  val workDir: java.nio.file.Path                 = ???
  val knockerUpper                                = FilesystemKnockerUpper.create(workDir)
  val runtime: WorkflowRuntime[IO, MyWorkflowCtx] = createRuntime(knockerUpper)

  val process: ResourceIO[Unit] = knockerUpper.initialize(Seq(runtime))
  // docs_end

}
