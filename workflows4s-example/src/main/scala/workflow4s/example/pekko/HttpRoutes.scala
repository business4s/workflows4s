package workflow4s.example.pekko

import cats.effect.unsafe.IORuntime
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport
import io.circe.{Encoder, Json, KeyEncoder}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import workflow4s.example.withdrawal.WithdrawalData
import workflow4s.example.withdrawal.WithdrawalService.Iban
import workflow4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.withdrawal.checks.{CheckKey, CheckResult, ChecksInput}

class HttpRoutes(actorSystem: ActorSystem[?], service: WithdrawalWorkflowService)(implicit ioRuntime: IORuntime) extends FailFastCirceSupport {
  implicit val checksInput: Encoder[ChecksInput]                   = Encoder.instance(_.checks.keys.map(_.value).asJson)
  implicit val checksResult: Encoder[CheckResult]                  = Encoder.instance(_ => Json.Null)
  implicit val checksResultFinished: Encoder[CheckResult.Finished] = Encoder.instance(_ => Json.Null)
  implicit val stateEncoder: Encoder.AsObject[WithdrawalData]      = deriveEncoder[WithdrawalData]

  val routes: Route = {
    path("withdrawals") {
      get {
        onSuccess(service.listWorkflows.unsafeToFuture()) { list =>
          complete(list)
        }
      }
    } ~
      path("withdrawals" / Segment) { id =>
        post {
          onSuccess(service.startWorkflow(id, CreateWithdrawal(100, Iban("123"))).unsafeToFuture()) {
            complete("OK")
          }
        } ~
          get {
            onSuccess(service.getState(id).unsafeToFuture()) { state =>
              complete(state)
            }
          }
      }
  }
}
