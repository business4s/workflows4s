package workflow4s.example

import io.circe.syntax.*
import io.circe.{Json, Printer}
import org.camunda.bpm.model.bpmn.Bpmn
import workflow4s.bpmn.BPMNConverter
import workflow4s.wio.WIO
import workflow4s.wio.model.WIOModelInterpreter

import java.io.File
import java.nio.file.{Files, Path}

object TestUtils {

  val basePath = Path.of(scala.sys.env.getOrElse("RENDER_OUT_DIR", "."))

  def renderModelToFile(wio: WIO[?, ?, ?, ?], path: String) = {
    val model           = WIOModelInterpreter.run(wio)
    val modelJson: Json = model.asJson
    Files.writeString(basePath.resolve(s"workflows4s-example/src/test/resources/${path}").toAbsolutePath, jsonPrinter.print(modelJson))
  }
  def renderBpmnToFile(wio: WIO[?, ?, ?, ?], path: String)  = {
    val model     = WIOModelInterpreter.run(wio)
    val bpmnModel = BPMNConverter.convert(model, "process")
    Bpmn.writeModelToFile(basePath.resolve(s"workflows4s-example/src/test/resources/${path}").toFile.getAbsoluteFile, bpmnModel)
  }

  def renderDocsExample(wio: WIO[?, ?, ?, ?], name: String) = {
    renderModelToFile(wio, s"docs/${name}.json")
    renderBpmnToFile(wio, s"docs/${name}.bpmn")
  }

  val jsonPrinter = Printer.spaces2

}
