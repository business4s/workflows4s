package workflows4s.example

import io.circe.syntax.*
import io.circe.{Json, Printer}
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalatest.exceptions.TestFailedException
import workflows4s.bpmn.BPMNConverter
import workflows4s.mermaid.MermaidRenderer
import workflows4s.wio.WIO

import java.nio.file.{Files, Path}

object TestUtils {

  val basePath = Path.of(scala.sys.env.getOrElse("RENDER_OUT_DIR", "."))

  val jsonPrinter                                           = Printer.spaces2
  def renderModelToFile(wio: WIO[?, ?, ?, ?], path: String) = {
    val model           = wio.toProgress.toModel
    val modelJson: Json = model.asJson
    val outputPath      = basePath.resolve(s"workflows4s-example/src/test/resources/${path}")
    ensureFileContentMatchesOrUpdate(jsonPrinter.print(modelJson), outputPath)
  }

  def renderBpmnToFile(wio: WIO[?, ?, ?, ?], path: String) = {
    val model       = wio.toProgress.toModel
    val bpmnModel   = BPMNConverter.convert(model, "process")
    val outputPath  = basePath.resolve(s"workflows4s-example/src/test/resources/${path}")
    val bpmnContent = Bpmn.convertToString(bpmnModel)

    ensureFileContentMatchesOrUpdate(bpmnContent, outputPath)
  }

  def renderMermaidToFile(wio: WIO[?, ?, ?, ?], path: String) = {
    val model      = wio.toProgress
    val flowchart  = MermaidRenderer.renderWorkflow(model)
    val outputPath = basePath.resolve(s"workflows4s-example/src/test/resources/${path}")

    ensureFileContentMatchesOrUpdate(flowchart.render, outputPath)
  }

  def renderDocsExample(wio: WIO[?, ?, ?, ?], name: String) = {
    renderModelToFile(wio, s"docs/${name}.json")
    renderBpmnToFile(wio, s"docs/${name}.bpmn")
    renderMermaidToFile(wio, s"docs/${name}.mermaid")
  }

  private def ensureFileContentMatchesOrUpdate(content: String, path: Path): Unit = {
    val absolutePath = path.toAbsolutePath
    def writeAndFail = {
      Files.writeString(absolutePath, content)
      new TestFailedException(
        s"File content mismatch at $absolutePath. Expected content has been written to the file. Please verify and commit the changes.",
        0,
      )
    }

    if (!Files.exists(absolutePath)) {
      writeAndFail
    }
    val existingContent = Files.readString(absolutePath)
    if (existingContent != content) {
      writeAndFail
    }
  }
}
