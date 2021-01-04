package org.neo4j.cypher.internal.compiler.v2_3.planner.act

import org.neo4j.cypher.internal.compiler.v2_3.commands.expressions.Identifier
import org.neo4j.cypher.internal.compiler.v2_3.commands.predicates.{Equals, HasLabel, Not}
import org.neo4j.cypher.internal.compiler.v2_3.planner.{CardinalityEstimation, PlannerQuery, UnionQuery}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans.{AllNodesScan, Expand, IdName, LogicalPlan, ProduceResult, Projection, Selection, StrictnessMode}
import org.neo4j.cypher.internal.frontend.v2_3.ast.Expression

import scala.collection.mutable

object NaivePlanner {
  def plan(query: UnionQuery, ctx: LogicalPlanningContext): LogicalPlan = {
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

  def canJoin(other: N_QG): Boolean = nodes.intersect(other.nodes).nonEmpty || edges.intersect(other.edges).nonEmpty
}

class N_TEntry(){

}
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
        val candidateFromExpand = table.map(expandQG).flatten.map(e=>(e._1, N_plan.expand(e))).toSet
        val candidateFromJoin = enumTwoDiffKey(table.keys.toArray).filter(canJoin).map(e=>(e._1, N_plan.join(e))).toSet
        updateTable(candidateFromExpand)
        updateTable(candidateFromJoin)

        table.find(entry => entry._1.covers(qg)) match {
          case Some(fulfill) => return fulfill._2
        }
      }while(true)

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

  def updateTable(candidates: Set[(N_QG, N_Op)]):Unit={
    candidates.foreach(entry=>{
      table.get(entry._1) match {
        case Some(value) =>
          val betterOne = compareCostSelectBestCandidate(entry._2, value)
          table.put(entry._1, betterOne)
        case _ => table.put(entry._1, entry._2)
      }
    })
  }

  def compareCostSelectBestCandidate(op1: N_Op, op2: N_Op):N_Op={
    val cost1 = N_CostModel.calc(op1)
    val cost2 = N_CostModel.calc(op2)
    if(cost1 < cost2) op1 else op2
  }

  def expandQG(entry:(N_QG, N_Op))(implicit origin: N_QG):Seq[(N_QG, N_Op)]={
    val cur = entry._1
    for(edge <- origin.edges){

      if(!cur.edges.contains(edge)){
        val containStartNode = origin.nodes.contains(edge._2)
        val containEndNode = origin.nodes.contains(edge._3)
        if(containEndNode && containStartNode){

        }

      }
    }

    Seq((N_QG(),new N_Op))
  }

  def enumTwoDiffKey(arr: Array[N_QG]): IndexedSeq[(N_QG, N_QG)] ={
    for{
      i <- 0.until(arr.length - 2)
      j <- (i+1).until(arr.length - 1)
    } yield (arr(i), arr(j))
  }

  def canJoin(qgs:(N_QG, N_QG)):Boolean = qgs._1.canJoin(qgs._2)
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
      case op@O_AllNodeScan() => op.cardinality * 10
      case op@O_NodeIndexSeek(_) => op.cardinality * 20
      case op@O_Expand() => op.cardinality * 15
      case op@O_Filter() => op.cardinality * 1
      case op@O_Join() => op.cardinality * 1
      case op@O_Projection() => op.cardinality * 1
      case _ => 0
    })
  }
}

object N_Statistic{

}

object N_plan{
  def allNodeScan(id:String):(N_QG, N_Op) = (N_QG(nodes = Set(id)), O_AllNodeScan())

  def nodeIndexSeek(id:String, value:String):(N_QG, N_Op) = (N_QG(nodes = Set(id)), O_NodeIndexSeek(value))

  def expand(entry:(N_QG, N_Op)):N_Op = {
    O_Expand()
  }

  def join(qgs:(N_QG, N_QG)):N_Op = {
    O_Join()
  }
}

class N_Op{
  var parent: Option[N_Op] = None
  var left: Option[N_Op] = None
  var right: Option[N_Op] = None
  var cardinality: Int = 0
}

case class O_AllNodeScan() extends N_Op {

}

case class O_NodeIndexSeek(value:String) extends N_Op {

}

case class O_Expand() extends N_Op {

}

case class O_Filter() extends N_Op {

}

case class O_Projection() extends N_Op {

}

case class O_Join() extends N_Op {

}
