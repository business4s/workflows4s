package workflow4s.example.pekko

import scala.concurrent.Future

import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import workflow4s.example.pekko.WithdrawalShard.{Command, TypeKey}
import workflow4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflow4s.runtime.RunningWorkflow
import workflows4s.runtime.pekko.{PekkoRunningWorkflow, WorkflowBehavior}

class WithdrawalShard(region: ActorRef[ShardingEnvelope[Command]])(using system: ActorSystem[Any]) {
  val sharding: ClusterSharding                                                       = ClusterSharding(system)
  def workflowInstance(withdrawalId: String): RunningWorkflow[Future, WithdrawalData] =
    PekkoRunningWorkflow(sharding.entityRefFor(TypeKey, withdrawalId))
}

object WithdrawalShard {
  type Command = WorkflowBehavior.Command[WithdrawalWorkflow.Context.Ctx]
  val TypeKey = EntityTypeKey[Command]("withdrawal")

  def create(withdrawalWorkflow: WithdrawalWorkflow)(using
      ioRuntime: IORuntime,
      system: ActorSystem[Any],
  ): WithdrawalShard = {
    val sharding    = ClusterSharding(system)
    val shardRegion = sharding.init(
      Entity(TypeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        WorkflowBehavior(persistenceId, withdrawalWorkflow.workflowDeclarative, WithdrawalData.Empty(persistenceId.toString))
      }),
    )
    WithdrawalShard(shardRegion)
  }

}
