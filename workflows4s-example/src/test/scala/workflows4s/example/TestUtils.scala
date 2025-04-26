package workflows4s.example

import io.circe.syntax.*
import io.circe.{Json, Printer}
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalatest.exceptions.TestFailedException
import workflows4s.bpmn.BpmnRenderer
import workflows4s.mermaid.MermaidRenderer
import workflows4s.wio.WIO
import workflows4s.wio.model.WIOExecutionProgress

import java.nio.file.{Files, Path, Paths}

object TestUtils {

  val basePath = Paths
    .get(getClass.getResource("/").toURI) // workflows4s-example/target/scala-3.4.2/test-classes
    .getParent                            // workflows4s-example/target/scala-3.4.2
    .getParent                            // workflows4s-example/target
    .getParent                            // workflows4s-example
    .resolve("src/test/resources")

  val jsonPrinter                                           = Printer.spaces2
  def renderModelToFile(wio: WIO[?, ?, ?, ?], path: String) = {
    val model           = wio.toProgress.toModel
    val modelJson: Json = model.asJson
    val outputPath      = basePath.resolve(path)
    ensureFileContentMatchesOrUpdate(jsonPrinter.print(modelJson), outputPath)
  }

  def renderBpmnToFile(wio: WIO[?, ?, ?, ?], path: String) = {
    val model       = wio.toProgress.toModel
    val bpmnModel   = BpmnRenderer.renderWorkflow(model, "process")
    val outputPath  = basePath.resolve(path)
    val bpmnContent = Bpmn.convertToString(bpmnModel)

    ensureFileContentMatchesOrUpdate(bpmnContent, outputPath)
  }

  def renderMermaidToFile(wio: WIO[?, ?, ?, ?], path: String, technical: Boolean = false) = {
    val model      = wio.toProgress
    val flowchart  = MermaidRenderer.renderWorkflow(model, showTechnical = technical)
    val outputPath = basePath.resolve(path)

    ensureFileContentMatchesOrUpdate(flowchart.render, outputPath)
  }

  def renderMermaidToFile(model: WIOExecutionProgress[?], path: String) = {
    val flowchart  = MermaidRenderer.renderWorkflow(model)
    val outputPath = basePath.resolve(path)

    ensureFileContentMatchesOrUpdate(flowchart.render, outputPath)
  }

  def renderDocsExample(wio: WIO[?, ?, ?, ?], name: String, technical: Boolean = false) = {
    renderModelToFile(wio, s"docs/${name}.json")
    renderBpmnToFile(wio, s"docs/${name}.bpmn")
    renderMermaidToFile(wio, s"docs/${name}.mermaid", technical)
  }

  private def ensureFileContentMatchesOrUpdate(content: String, path: Path): Unit = {
    val absolutePath         = path.toAbsolutePath
    Files.createDirectories(absolutePath.getParent)
    def writeAndFail(): Unit = {
      Files.writeString(absolutePath, content)
      throw new TestFailedException(
        s"File content mismatch at $absolutePath. Expected content has been written to the file. Please verify and commit the changes.",
        0,
      )
    }

    if (!Files.exists(absolutePath)) {
      writeAndFail()
    }
    val existingContent = Files.readString(absolutePath)
    if (existingContent != content) {
      writeAndFail()
    }
  }
}
