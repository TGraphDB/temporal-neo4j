package org.neo4j.cypher.internal.compiler.v2_3.planner.act

import org.neo4j.cypher.internal.compiler.v2_3.planner.{CardinalityEstimation, PlannerQuery, QueryGraph}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans.{IdName, LogicalLeafPlan, LogicalPlan}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.{LeafPlanner, LogicalPlanningContext}
import org.neo4j.cypher.internal.frontend.v2_3.ast.{Expression, Identifier}

abstract class TemporalValueIndexScanPlanner extends LeafPlanner{

}

object temporalIndexIdSeekPlanner extends TemporalValueIndexScanPlanner{
  override def apply(queryGraph: QueryGraph)(implicit context: LogicalPlanningContext): Seq[LogicalPlan] = {
    //如果在predicate中含有tp_within*函数则可以返回这个Operator。现在可以不搞在点上的，只搞边上的。
    //TODO:支持node上用时态属性索引检索id列表的Operator
    val predicates: Seq[Expression] = queryGraph.selections.flatPredicates
    val arguments = queryGraph.argumentIds.map(n => Identifier(n.name)(null))

//    val idSeekPredicates: Seq[(Expression, Identifier, SeekableArgs)] = predicates.collect {
//      // MATCH (a)-[r]->b WHERE id(r) IN expr
//      // MATCH a WHERE id(a) IN {param}
//      case predicate@AsIdSeekable(seekable) if seekable.args.dependencies.forall(arguments) && !arguments(seekable.ident) =>
//        (predicate, seekable.ident, seekable.args)
//    }
//
//    val candidatePlans = idSeekPredicates.collect {
//      case (predicate, idExpr @ Identifier(idName), idValues) if !queryGraph.argumentIds.contains(IdName(idName)) =>
//        queryGraph.patternRelationships.find(_.name.name == idName) match {
//          case Some(relationship) =>
//            val types = relationship.types.toList
//            val seekPlan = planRelationshipByIdSeek(relationship, idValues, Seq(predicate), queryGraph.argumentIds)
//            planRelTypeFilter(seekPlan, idExpr, types)
//          case None =>
//            context.logicalPlanProducer.planNodeByIdSeek(IdName(idName), idValues, Seq(predicate), queryGraph.argumentIds)
//        }
//    }

//    candidatePlans
    Seq()
  }
}


case class RelIdSeekByTemporalIndex() extends LogicalLeafPlan {
  override def argumentIds: Set[IdName] = ???
  override def solved: PlannerQuery with CardinalityEstimation = ???
  //  override def solved: PlannerQuery with CardinalityEstimation = PlannerQuery(graph = QueryGraph.empty
//    .addPatternRelationship(pattern)
//    .addPredicates(solvedPredicates: _*)
//    .addArgumentIds(argumentIds.toSeq)
//  )

  override def availableSymbols: Set[IdName] = Set(IdName(""))

  override def mapExpressions(f: (Set[IdName], Expression) => Expression): LogicalPlan = ???
}
