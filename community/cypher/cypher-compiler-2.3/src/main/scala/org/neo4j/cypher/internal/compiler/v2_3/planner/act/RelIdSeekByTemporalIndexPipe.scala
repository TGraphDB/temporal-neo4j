package org.neo4j.cypher.internal.compiler.v2_3.planner.act

import org.neo4j.cypher.internal.compiler.v2_3.ExecutionContext
import org.neo4j.cypher.internal.compiler.v2_3.executionplan.{Effects, ReadsAllNodes, ReadsRelationships}
import org.neo4j.cypher.internal.compiler.v2_3.helpers.CollectionSupport
import org.neo4j.cypher.internal.compiler.v2_3.pipes.{Pipe, PipeMonitor, QueryState, RonjaPipe}
import org.neo4j.cypher.internal.compiler.v2_3.planDescription.InternalPlanDescription.Arguments
import org.neo4j.cypher.internal.compiler.v2_3.planDescription.{InternalPlanDescription, NoChildren, PlanDescriptionImpl}
import org.neo4j.cypher.internal.compiler.v2_3.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v2_3.symbols.{CTNode, CTRelationship}
import org.neo4j.graphdb.Relationship

/**
 * copied from DirectedRelationshipByIdSeekPipe 照猫画虎
 */
case class RelIdSeekByTemporalIndexPipe(ident: String, fromNode: String, toNode: String,
                                        tStart: Int, tEnd: Int,
                                        propertyName:String, vMin: Int, vMax: Int)
                                       (val estimatedCardinality: Option[Double] = None)
                                       (implicit pipeMonitor: PipeMonitor)
  extends Pipe
  with CollectionSupport
  with RonjaPipe {

  override protected def internalCreateResults(state: QueryState): Iterator[ExecutionContext] = {
    state.decorator.registerParentPipe(this)

    val s = new org.neo4j.temporal.TimePoint(tStart)
    val e = new org.neo4j.temporal.TimePoint(tEnd)
    val idList = state.query.relationshipOps.getEntityIdListByTemporalPropertyValueIndex(propertyName, s, e, vMin, vMax)

    val ctx = state.initialContext.getOrElse(ExecutionContext.empty)
    val ops = state.query.relationshipOps
    idList.map(rId=>{
      val r:Relationship = ops.getById(rId)
      ctx.newWith3(ident, r, fromNode, r.getStartNode, toNode, r.getEndNode)
    })
  }

  override def planDescriptionWithoutCardinality: InternalPlanDescription = PlanDescriptionImpl(
    id = id,
    name = "DirectedRelationshipByIdSeekPipe",
    children = NoChildren,
    arguments = Seq(Arguments.Time(tStart), Arguments.Time(tEnd), Arguments.KeyNames(Seq(propertyName)), Arguments.Time(vMin), Arguments.Time(vMax)),
    identifiers
  )

  override def symbols: SymbolTable = SymbolTable(Map(ident -> CTRelationship, toNode -> CTNode, fromNode -> CTNode))

  override def sources: Seq[Pipe] = Seq.empty

  override def localEffects: Effects = Effects(ReadsAllNodes, ReadsRelationships)

  override def exists(predicate: Pipe => Boolean): Boolean = predicate(this)

  override def withEstimatedCardinality(estimated: Double): Pipe with RonjaPipe = copy()(Some(estimated))

  override def monitor: PipeMonitor = pipeMonitor

  override def dup(sources: List[Pipe]): Pipe = {
    require(sources.isEmpty)
    this
  }
}
