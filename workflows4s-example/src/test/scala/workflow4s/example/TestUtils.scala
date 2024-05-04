package workflow4s.example

import io.circe.syntax.*
import io.circe.{Json, Printer}
import org.camunda.bpm.model.bpmn.Bpmn
import workflow4s.bpmn.BPMNConverter
import workflow4s.wio.WIO
import workflow4s.wio.model.WIOModelInterpreter

import java.io.File
import java.nio.file.Files

object TestUtils {

  def renderModelToFile(wio: WIO[?, ?, ?, ?], path: String) = {
    val model           = WIOModelInterpreter.run(wio)
    val modelJson: Json = model.asJson
    Files.writeString(java.nio.file.Path.of(s"workflows4s-example/src/test/resources/${path}").toAbsolutePath, jsonPrinter.print(modelJson))
  }
  def renderBpmnToFile(wio: WIO[?, ?, ?, ?], path: String)  = {
    val model     = WIOModelInterpreter.run(wio)
    val bpmnModel = BPMNConverter.convert(model, "process")
    Bpmn.writeModelToFile(new File(s"workflows4s-example/src/test/resources/${path}").getAbsoluteFile, bpmnModel)
  }

  def renderDocsExample(wio: WIO[?, ?, ?, ?], name: String) = {
    renderModelToFile(wio, s"docs/${name}.json")
    renderBpmnToFile(wio, s"docs/${name}.bpmn")
  }

  val jsonPrinter = Printer.spaces2

}
