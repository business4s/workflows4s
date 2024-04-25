package workflow4s.example.pekko

import cats.effect.unsafe.IORuntime
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

class HttpRoutes(actorSystem: ActorSystem[_], service: WithdrawalWorkflowService)(implicit ioRuntime: IORuntime) extends FailFastCirceSupport {
  val routes: Route             = {
    path("withdrawals") {
      get {
        onSuccess(service.listWorkflows.unsafeToFuture()) { list =>
          complete(list)
        }
      }
    }
  }
}
