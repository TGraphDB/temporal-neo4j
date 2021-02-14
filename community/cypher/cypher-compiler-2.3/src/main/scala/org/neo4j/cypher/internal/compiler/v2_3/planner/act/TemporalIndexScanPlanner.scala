package org.neo4j.cypher.internal.compiler.v2_3.planner.act

import org.neo4j.cypher.internal.compiler.v2_3.commands.expressions.TPWithinExistFunction
import org.neo4j.cypher.internal.compiler.v2_3.planner.{CardinalityEstimation, PlannerQuery, QueryGraph}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans.{AsIdSeekable, IdName, LogicalLeafPlan, LogicalPlan, SeekableArgs}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.steps.idSeekLeafPlanner.planRelTypeFilter
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.{Cardinality, LeafPlanner, LogicalPlanningContext}
import org.neo4j.cypher.internal.frontend.v2_3.ast.{Expression, FunctionInvocation, FunctionName, Identifier, Parameter, Property, PropertyKeyName, functions}
import org.neo4j.cypher.internal.frontend.v2_3.ast.functions.TPWithinExistsFunc

abstract class TemporalValueIndexScanPlanner extends LeafPlanner

object temporalIndexIdSeekPlanner extends TemporalValueIndexScanPlanner{
  override def apply(queryGraph: QueryGraph)(implicit context: LogicalPlanningContext): Seq[LogicalPlan] = {
    //使用该Operator的条件：
    // 1. WHERE中无OR TODO:不知怎么搞
    // 2. WHERE（predicate）中含有tp_within*函数。
    // 3. 上述函数的第一个参数是边的时态属性。目前只搞边上的。TODO:支持node上用时态属性索引检索id列表的Operator
    // 4. 上述函数的时间段参数被索引完全覆盖 TODO: 支持不被索引完全覆盖的情况

    queryGraph.selections.flatPredicates.collect {
      case predicate@FunctionInvocation(FunctionName(name), _, Vector(Property(idExpr@Identifier(entityIdName), PropertyKeyName(prop)), tStart:Parameter, tEnd:Parameter, vMin:Parameter, vMax:Parameter))
        if {
          val func = predicate.function.get.asInstanceOf[TPWithinExistsFunc.type]
          val s = func.startTime.toInt
          val e = func.endTime.toInt
          name=="tp_within_exists" && context.planContext.getEntitySeekTemporalIndex(s, e, prop)
        } => //条件2、4
        queryGraph.patternRelationships.find(_.name.name == entityIdName) match {
          case Some(relationship) => //条件3
            val (startNode, endNode) = relationship.nodes
            val solved = PlannerQuery(graph = QueryGraph.empty
              .addPatternRelationship(relationship)
              .addPredicates(predicate)
              .addArgumentIds(queryGraph.argumentIds.toSeq))
            val f = predicate.function.get.asInstanceOf[TPWithinExistsFunc.type]
            val solvedWithCardinality = estimatePlannerQuery(solved, f.startTime.toInt, f.endTime.toInt, prop, f.vvMin.toInt, f.vvMax.toInt)
            val seekPlan = RelIdSeekByTemporalIndex(relationship.name, startNode, endNode,
              tStart, tEnd, prop, vMin, vMax,
              queryGraph.argumentIds)(solvedWithCardinality)
            planRelTypeFilter(seekPlan, idExpr, relationship.types.toList)
          case _ => throw new RuntimeException("node temporal index not supported yet")
        }
    }
  }


  private implicit def estimatePlannerQuery(plannerQuery: PlannerQuery,
                                            tStart: Int, tEnd: Int,
                                            prop:String, vMin:Int, vMax:Int
                                           )(implicit context: LogicalPlanningContext): PlannerQuery with CardinalityEstimation = {
//    val cardinality = cardinalityModel(plannerQuery, context.input, context.semanticTable)
    val c = context.planContext.statistics.cardinalityByTemporalPropertyValueIndex(tStart, tEnd, prop, vMin, vMax)
    CardinalityEstimation.lift(plannerQuery, c)
  }
}

//几乎完全照搬DirectedRelationshipByIdSeek
case class RelIdSeekByTemporalIndex( idName: IdName,
                                     startNode: IdName, endNode: IdName,
                                     tStart: Parameter, tEnd: Parameter,
                                     propertyName:String, vMin: Parameter, vMax: Parameter,
                                     argumentIds: Set[IdName])(val solved: PlannerQuery with CardinalityEstimation)
  extends LogicalLeafPlan {

  override def availableSymbols: Set[IdName] = argumentIds ++ Set(idName, startNode, endNode)

  override def mapExpressions(f: (Set[IdName], Expression) => Expression): LogicalPlan = this
}
