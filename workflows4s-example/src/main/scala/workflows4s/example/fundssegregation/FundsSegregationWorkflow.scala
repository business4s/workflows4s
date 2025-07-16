package workflows4s.example.fundssegregation

import workflows4s.mermaid.MermaidRenderer

import scala.concurrent.duration.DurationInt

object FundsSegregationWorkflow {

  import workflows4s.wio.DraftWorkflowContext.*

  val initialize = WIO.draft.step(
    error = "Previous Instance Running",
    description = """• Check previous run
                    |• Fetch DueToSBorg split
                    |• Calculate trade plan""".stripMargin,
  )

  object TradeWorkflow {
    private val executeOrder   = WIO.draft.step().retryIn(_ => ???)
    private val orderCompleted = WIO.draft.signal()
    private val executionLoop  = WIO.draft.repeat("Whole amount exchanged?", "Yes", "No")(executeOrder >>> orderCompleted)
    private val keepTheAsset   = WIO.draft.step()
    private val timeout        = WIO.draft.timer(duration = 1.hour).toInterruption.andThen(_ >>> keepTheAsset)

    val workflow = executionLoop.interruptWith(timeout)
  }

  val executeTrades = WIO.draft.forEach(TradeWorkflow.workflow)

  val computeTransferPlan = WIO.draft.step()

  object TransferWorkflow {
    private val executeTransfer   = WIO.draft.step().retryIn(_ => ???)
    private val transferCompleted = WIO.draft.signal()

    val workflow = executeTransfer >>> transferCompleted
  }
  val executeTransfers = WIO.draft.forEach(TransferWorkflow.workflow)

  val recordTransfers = WIO.draft.step()

  val terminate = WIO.draft.step()

  val workflow =
    (initialize >>> executeTrades >>> computeTransferPlan >>> executeTransfers >>> recordTransfers)
      .handleErrorWith(terminate)

  def main(args: Array[String]): Unit = {
    val chart = MermaidRenderer.renderWorkflow(workflow.toProgress, showTechnical = true)
    println(chart.toViewUrl)
  }

}
