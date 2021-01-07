package org.neo4j.cypher.internal.compiler.v2_3.planner.act

import org.neo4j.cypher.internal.compiler.v2_3.planner.{CardinalityEstimation, PlannerQuery, QueryGraph}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans.{AsIdSeekable, IdName, LogicalLeafPlan, LogicalPlan, SeekableArgs}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.steps.idSeekLeafPlanner.planRelTypeFilter
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.{Cardinality, LeafPlanner, LogicalPlanningContext}
import org.neo4j.cypher.internal.frontend.v2_3.ast.{Expression, FunctionInvocation, Identifier, functions}

abstract class TemporalValueIndexScanPlanner extends LeafPlanner{

}

object temporalIndexIdSeekPlanner extends TemporalValueIndexScanPlanner{
  override def apply(queryGraph: QueryGraph)(implicit context: LogicalPlanningContext): Seq[LogicalPlan] = {
    //使用该Operator的条件：
    // 1. WHERE中无OR
    // 2. WHERE（predicate）中含有tp_within*函数。
    // 3. 上述函数的第一个参数是边的时态属性。目前只搞边上的。TODO:支持node上用时态属性索引检索id列表的Operator
    // 4. 上述函数的时间段参数被索引完全覆盖 TODO: 支持不被索引完全覆盖的情况

    val predicates: Seq[Expression] = queryGraph.selections.flatPredicates
    val arguments = queryGraph.argumentIds.map(n => Identifier(n.name)(null))

    val idSeekPredicates: Seq[(Expression, Identifier, SeekableArgs)] = predicates.collect {
      // MATCH (a)-[r]->b WHERE id(r) IN expr
      // MATCH a WHERE id(a) IN {param}
      case predicate@AsIdSeekable(seekable) if seekable.args.dependencies.forall(arguments) && !arguments(seekable.ident) =>
        (predicate, seekable.ident, seekable.args)
//      case func:FunctionInvocation if func.function.contains(functions.TPWithinExistsFunc) =>

    }

    val candidatePlans = idSeekPredicates.collect {
      case (predicate, idExpr @ Identifier(idName), _) if !queryGraph.argumentIds.contains(IdName(idName)) =>
        queryGraph.patternRelationships.find(_.name.name == idName) match {
          case Some(relationship) =>
            val types = relationship.types.toList
            val (startNode, endNode) = relationship.nodes

//            context.logicalPlanProducer.planRelIdSeekByTemporalIndex()
            val solved = PlannerQuery(graph = QueryGraph.empty
              .addPatternRelationship(relationship)
              .addPredicates(predicate)
              .addArgumentIds(queryGraph.argumentIds.toSeq))
            val seekPlan = RelIdSeekByTemporalIndex(relationship.name, startNode, endNode,
              0, 100,
              "travel_time", 0, 200,
              queryGraph.argumentIds)(solved)
            planRelTypeFilter(seekPlan, idExpr, types)
//          case None =>
//            context.logicalPlanProducer.planNodeByIdSeek(IdName(idName), idValues, Seq(predicate), queryGraph.argumentIds)
        }
    }

//    candidatePlans

    Seq()
  }


  private implicit def estimatePlannerQuery(plannerQuery: PlannerQuery)(implicit context: LogicalPlanningContext): PlannerQuery with CardinalityEstimation = {
//    val cardinality = cardinalityModel(plannerQuery, context.input, context.semanticTable)
    CardinalityEstimation.lift(plannerQuery, Cardinality(10))
  }
}

//几乎完全照搬DirectedRelationshipByIdSeek
case class RelIdSeekByTemporalIndex( idName: IdName,
                                     startNode: IdName, endNode: IdName,
                                     tStart: Int, tEnd: Int,
                                     propertyName:String, vMin: Int, vMax: Int,
                                     argumentIds: Set[IdName])(val solved: PlannerQuery with CardinalityEstimation)
  extends LogicalLeafPlan {

  override def availableSymbols: Set[IdName] = argumentIds ++ Set(idName, startNode, endNode)

  override def mapExpressions(f: (Set[IdName], Expression) => Expression): LogicalPlan = this
}
