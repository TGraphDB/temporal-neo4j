package org.neo4j.cypher.internal.compiler.v2_3.planner.act

import org.neo4j.cypher.internal.compiler.v2_3.commands.expressions.Identifier
import org.neo4j.cypher.internal.compiler.v2_3.commands.predicates.{Equals, HasLabel, Not}
import org.neo4j.cypher.internal.compiler.v2_3.planner.{CardinalityEstimation, PlannerQuery, UnionQuery}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.Metrics.CardinalityModel
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans.{AllNodesScan, Expand, IdName, LogicalPlan, ProduceResult, Projection, Selection, StrictnessMode}
import org.neo4j.cypher.internal.frontend.v2_3.ast.Expression

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object NaivePlanner {
  def plan(query: UnionQuery, ctx: LogicalPlanningContext, cardinalityModel: CardinalityModel): LogicalPlan = {
    println(query.queries.length)
    println(query.queries.head)
    val qg = query.queries.head.graph
    println(qg)
    println(query.returns)
    val cardinality = ctx.metrics.cardinality(query.queries.head, ctx.input, ctx.semanticTable)
    println(cardinality)
    println(ctx.metrics.cost)

    val plan:LogicalPlan = new LogicalPlan {
      override def lhs: Option[LogicalPlan] = ???
      override def rhs: Option[LogicalPlan] = ???

      override def solved: PlannerQuery with CardinalityEstimation = ???
      override def availableSymbols: Set[IdName] = ???
      override def mapExpressions(f: (Set[IdName], Expression) => Expression): LogicalPlan = ???
      override def productElement(n: Int): Any = ???
      override def productArity: Int = ???
      override def canEqual(that: Any): Boolean = ???
      override def strictness: StrictnessMode = ???
    }
//    ctx.metrics.cost(plan, ctx.input)
//    plan.solved.estimatedCardinality

    plan
//    ProduceResult(
//      Seq("colName0", "colName1"),
//      Projection(
//        Selection(
//          Seq(Not(Equals(Identifier("x"), Identifier("y")))),
//          Expand(
//            Selection(
//              Seq(Not(Equals(Identifier("z"), Identifier("w")))),
//              Expand(
//                Selection(Seq(HasLabel("User")))
//              )
//            )
//          )
//        ), Expression()))
  }
}

case class N_QG(nodes: Set[String] = Set.empty, //点的id
                edges: Set[(String, String, String)] = Set.empty, //边的id，起点id，终点id
                selections: Set[(String, String)] = Set.empty //点or边id，name属性的条件值
                  ){
  def addPatternNode(id:String):N_QG = copy(nodes = nodes + id)
  def addPatternEdge(id:String, from:String, to:String):N_QG = copy(edges = edges + ((id, from, to)))
  def addSelection(id:String, value:String):N_QG = copy(selections = selections + ((id, value)))

  private def copy(nodes: Set[String] = nodes,
                   edges: Set[(String, String, String)] = edges,
                   selections: Set[(String, String)] = selections) = N_QG(nodes, edges, selections)

  def covers(other: N_QG): Boolean = other.nodes.subsetOf(nodes) && other.edges.subsetOf(edges) && other.selections.subsetOf(selections)
}

//class N_TEntry(){
//
//}
class N_DP{
  var table: mutable.Map[N_QG, N_Op] = mutable.Map()

  def run(implicit qg: N_QG):N_Op = {
    //初始化：给所有node制定plan（Operator）
    val leafCandidates = qg.nodes.flatMap(id=>{
      val e = qg.selections.find(entry => {
        entry._1 == id
      })
      if(e.isDefined) {
        Seq(N_plan.allNodeScan(id), N_plan.nodeIndexSeek(id, e.get._2))
      }else{
        Seq(N_plan.allNodeScan(id))
      }
    })

    updateTable(leafCandidates)

    if(qg.edges.nonEmpty){
      //遍历table并试图组合扩展新的entry直到entry.key覆盖了qg
      do{
        val candidateFromExpand = table.flatMap(N_plan.expand).map(N_plan.filter).toSet
        val candidateFromJoin = enumTwoDiffKey(table.toArray).flatMap(N_plan.join).map(N_plan.filter).toSet
        updateTable(candidateFromExpand)
        updateTable(candidateFromJoin)

        table.find(entry => entry._1.covers(qg)) match {
          case Some(fulfill) => return fulfill._2
        }
      }while(true)
      throw new RuntimeException("TCypher(NaivePlanner): should not happen")
    }else{
      if(table.size==1){
        val entry = table.toSeq.head
        if(entry._1.covers(qg)) entry._2
        else throw new RuntimeException("TCypher(NaivePlanner): should not happen")
      }else{
        throw new RuntimeException("TCypher(NaivePlanner): should not happen")
      }
    }
  }

  def updateTable(candidates: Set[N_Op]):Unit={
    candidates.foreach(entry=>{
      table.get(entry.qg) match {
        case Some(value) =>
          val betterOne = compareCostSelectBestCandidate(entry, value)
          table.put(entry.qg, betterOne)
        case _ => table.put(entry.qg, entry)
      }
    })
  }

  def compareCostSelectBestCandidate(op1: N_Op, op2: N_Op):N_Op={
    val cost1 = N_CostModel.calc(op1)
    val cost2 = N_CostModel.calc(op2)
    if(cost1 < cost2) op1 else op2
  }

  def enumTwoDiffKey(arr: Array[(N_QG, N_Op)]): IndexedSeq[(N_Op, N_Op)] ={
    for{
      i <- 0.until(arr.length - 2)
      j <- (i+1).until(arr.length - 1)
    } yield (arr(i)._2, arr(j)._2)
  }
}

object N_CostModel{
  def calc(opRoot: N_Op): Double = {
    (opRoot.left match {
      case Some(op) => calc(op)
      case _ => 0
    }) + (opRoot.right match {
      case Some(op) => calc(op)
      case _ => 0
    }) + (opRoot match {
      case op:O_AllNodeScan => op.cardinality * 10
      case op:O_NodeIndexSeek => op.cardinality * 20
      case op:O_Expand => op.cardinality * 15
      case op:O_Filter => op.cardinality * 1
      case op:O_Join => op.cardinality * 1
      case op:O_Projection => op.cardinality * 1
      case _ => 0
    })
  }
}

object N_Statistic{
  def nodesWithName(name: String):Int = {
    3
  }

  val totalNodeCnt = 100
  val totalEdgeCnt = 200
}

//object N_Cardinality{
//
//}

object N_plan{
  def allNodeScan(id:String):N_Op = {
    val op = O_AllNodeScan()(qg = N_QG(nodes = Set(id)))
    op.cardinality = N_Statistic.totalNodeCnt
    op
  }

  def nodeIndexSeek(id:String, value:String):N_Op = {
    val op = O_NodeIndexSeek(value)(qg = N_QG(nodes = Set(id), selections = Set((id, value))))
    op.cardinality = N_Statistic.nodesWithName(value)
    op
  }

  def expand(entry:(N_QG, N_Op))(implicit origin: N_QG):Seq[N_Op]={
    val results: ListBuffer[N_Op] = ListBuffer()
    val curQG = entry._1
    val curOP = entry._2
    for(r <- origin.edges){
      if(!curQG.edges.contains(r)){
        val containStartNode = origin.nodes.contains(r._2)
        val containEndNode = origin.nodes.contains(r._3)
        if(containEndNode && containStartNode){
          val qg: N_QG = N_QG(nodes = curQG.nodes+r._3, edges=curQG.edges+r, selections = curQG.selections)
          val root = O_Expand("into", r._1, r._2, r._3)(qg)
          root.left = Some(curOP)
          results.append(root)
        }else if(containStartNode){
          val qg: N_QG = N_QG(nodes = curQG.nodes+r._2, edges=curQG.edges+r, selections = curQG.selections)
          val root = O_Expand("all", r._1, r._2, r._3)(qg)
          root.left = Some(curOP)
          results.append(root)
        }else if(containEndNode){
          val qg: N_QG = N_QG(nodes = curQG.nodes+r._3, edges=curQG.edges+r, selections = curQG.selections)
          val root = O_Expand("all", r._1, r._2, r._3)(qg)
          root.left = Some(curOP)
          results.append(root)
        }else{
          throw new RuntimeException("TCypher: should not happen")
        }
      }
    }
    results
  }

  def join(ops:(N_Op, N_Op))(implicit origin: N_QG):Seq[N_Op] = {
    val qg1 = ops._1.qg
    val qg2 = ops._2.qg
    val commonNodes = qg1.nodes.intersect(qg2.nodes)
    val commonEdges = qg1.edges.intersect(qg2.edges)
    //要求：有1或2个公共点，无公共边
    if(commonEdges.isEmpty && (commonNodes.size==1 || commonNodes.size==2)){

      Seq(O_Join()(N_QG()))
    }else{
      Seq()
    }
  }

  def filter(op:N_Op)(implicit origin: N_QG):N_Op = {
    val condition2filter = origin.selections.diff(op.qg.selections).filter(c => op.qg.nodes.contains(c._1))
    O_Filter(condition2filter)(op.qg)
  }
}

abstract class N_Op{
  val qg: N_QG = ???
//  var parent: Option[N_Op] = None
  var left: Option[N_Op] = None
  var right: Option[N_Op] = None
  var cardinality: Int = 0
//  def execute()
}

case class O_AllNodeScan()(implicit override val qg: N_QG) extends N_Op //{

//}

case class O_NodeIndexSeek(value:String)(implicit override val qg: N_QG) extends N_Op //{

//}

case class O_Expand(mode:String, r:String, from:String, to:String)(implicit override val qg: N_QG) extends N_Op //{

//}

case class O_Filter(cond:Set[(String, String)])(implicit override val qg: N_QG) extends N_Op //{

///}

case class O_Projection()(implicit override val qg: N_QG) extends N_Op //{

//}

case class O_Join()(implicit override val qg: N_QG) extends N_Op //{

//}
