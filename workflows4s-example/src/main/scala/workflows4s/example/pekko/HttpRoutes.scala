package workflows4s.example.pekko

import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import workflows4s.example.withdrawal.WithdrawalData
import workflows4s.example.withdrawal.WithdrawalService.{Fee, Iban}
import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflows4s.example.withdrawal.checks.{CheckResult, ChecksInput, ChecksState}
import io.circe.generic.auto.*

class HttpRoutes(service: FutureWithdrawalWorkflowService) extends FailFastCirceSupport {
  given checksInput[F[_]]: Encoder[ChecksInput[F]]          = Encoder.instance(_.checks.keys.map(_.value).asJson)
  given checksResult: Encoder[CheckResult]                  = Encoder.instance(_ => Json.Null)
  given checksResultFinished: Encoder[CheckResult.Finished] = Encoder.instance(_ => Json.Null)
  given Encoder[ChecksState]                                = Encoder.instance {
    case inProgress: ChecksState.InProgress =>
      inProgress.asJson
    case decided: ChecksState.Decided       =>
      decided.asJson
  }

  given Encoder[WithdrawalData] = Encoder.instance {
    case empty: WithdrawalData.Empty         =>
      empty.asJson
    case initiated: WithdrawalData.Initiated =>
      initiated.asJson
    case validated: WithdrawalData.Validated =>
      validated.asJson
    case checking: WithdrawalData.Checking   =>
      checking.asJson
    case checked: WithdrawalData.Checked     =>
      checked.asJson
    case executed: WithdrawalData.Executed   =>
      executed.asJson
    case completed: WithdrawalData.Completed =>
      Json.fromString("Completed")
  }
  val routes: Route             = {
    path("withdrawals") {
      get {
        onSuccess(service.listWorkflows) { list =>
          complete(list)
        }
      }
    } ~
      path("withdrawals" / Segment) { id =>
        post {
          onSuccess(service.startWorkflow(id, CreateWithdrawal(id, 100, Iban("123")))) {
            complete("OK")
          }
        } ~
          get {
            onSuccess(service.getState(id)) { state =>
              complete(state)
            }
          }
      }
  }
}
