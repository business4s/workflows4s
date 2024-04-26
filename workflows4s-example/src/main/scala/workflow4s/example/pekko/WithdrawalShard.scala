package workflow4s.example.pekko

import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import workflow4s.example.pekko.WithdrawalShard.{Command, TypeKey}
import workflow4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflows4s.runtime.pekko.WorkflowBehavior

class WithdrawalShard(system: ActorSystem[Any], region: ActorRef[ShardingEnvelope[Command]]) {
  def refFor(withdrawalId: String)(implicit system: ActorSystem[Any]): EntityRef[Command] =
    ClusterSharding(system).entityRefFor(TypeKey, withdrawalId)
}

object WithdrawalShard {
  type Command = WorkflowBehavior.Command[WithdrawalWorkflow.Context.type]
  val TypeKey = EntityTypeKey[Command]("withdrawal")

  def create(withdrawalWorkflow: WithdrawalWorkflow)(implicit
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
    WithdrawalShard(system, shardRegion)
  }

}
