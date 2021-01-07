package org.neo4j.cypher.internal.compiler.v2_3.commands.expressions

import org.act.temporalProperty.vo.EntityPropertyId
import org.neo4j.cypher.internal.compiler.v2_3.ExecutionContext
import org.neo4j.cypher.internal.compiler.v2_3.commands.predicates.TemporalContains
import org.neo4j.cypher.internal.compiler.v2_3.pipes.QueryState
import org.neo4j.cypher.internal.compiler.v2_3.spi.Operations
import org.neo4j.cypher.internal.compiler.v2_3.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v2_3.symbols.CypherType
import org.neo4j.cypher.internal.frontend.v2_3.symbols.CTBoolean
import org.neo4j.cypher.internal.frontend.v2_3.ast.{Parameter, TimeInterval, TimePoint, TimePointInit, TimePointNow, TimePointRegular, Expression => ASTExpression}
import org.neo4j.cypher.internal.frontend.v2_3.symbols._
import org.neo4j.graphdb.{Node, PropertyContainer, Relationship}
import org.neo4j.temporal.{TimePoint => TP}


/**
  * Created by song on 2018-08-20.
  */
abstract class TemporalValueCommandExpression extends Expression {
  def calculateType(symbols: SymbolTable): TemporalValueType = CTTValue
}

case class TemporalValueLiteral(items: Seq[(TimeInterval, ASTExpression)]) extends TemporalValueCommandExpression{
  def apply(ctx: ExecutionContext)(implicit state: QueryState):Seq[(Int, Int, Any)] = {
    items.map(entry=>{
      val timeInterval = entry._1
      val startT = parTime(timeInterval.startT)
      val endT = parTime(timeInterval.endT)
      val value = parVal(entry._2)
      value match {
        case v:Double => (startT, endT, v)
        case v:Long => (startT, endT, v)
        case x => throw new RuntimeException("TCypher Input Error: temporal value type must be Double or Long. got "+x.getClass.getName)
      }
    })
  }

  private def parTime(t:TimePoint)(implicit state:QueryState):Int = {
//    if(t.isInstanceOf[TimePointRegular]){}
    t match {
      case tt:TimePointRegular => tt.item match {
        case i:Parameter => state.getParam(i.name) match {
          case ttt: Long => Math.toIntExact(ttt)
          case x => throw new RuntimeException("TCypher SNH: value type mismatch. got "+ x.getClass.getName)
        }
        case x => throw new RuntimeException("TCypher SNH: value type mismatch. got "+ x.getClass.getName)
      }
      case tt:TimePointInit => tt.time
      case tt:TimePointNow => tt.time
      case x => throw new RuntimeException("TCypher SNH: value type mismatch. got "+ x.getClass.getName)
    }
  }

  private def parVal(e:ASTExpression)(implicit state:QueryState):Any = {
    e match {
      case i:Parameter => state.getParam(i.name)
      case x => throw new RuntimeException("TCypher SNH: value type mismatch. got "+ x.getClass.getName)
    }
  }

  override def toString: String = items.toString()

  def rewrite(f: (Expression) => Expression): Expression = f(this)

  def arguments = Seq()

  def symbolTableDependencies = Set()
}

/**
  * Created by song on 2018-12-11.
  */
case class TemporalContainsFunction(property: Expression, tVal: Expression) extends Expression{
  override def rewrite(f: (Expression) => Expression): Expression = TemporalContains(property, tVal).rewrite(f)

  override def arguments: Seq[Expression] = Seq(property, tVal)

  override def apply(ctx: ExecutionContext)(implicit state: QueryState): Any = {
    throw new RuntimeException("TGraph SNH: because this is rewrite to TemporalContains Expression so this should not be called.")
  }

  override protected def calculateType(symbols: SymbolTable): CypherType = CTBoolean

  override def toString: String = "TemporalContainsFunc("+property.toString()+")"

  override def symbolTableDependencies: Set[String] = Set()
}

case class TPWithinExistFunction(property: Expression, tStart: Expression, tEnd: Expression, vMin: Expression, vMax: Expression) extends Expression{
  override def rewrite(f: (Expression) => Expression): Expression = this

  override def arguments: Seq[Expression] = Seq(property, tStart, tEnd, vMin, vMax)

  override def apply(ctx: ExecutionContext)(implicit state: QueryState): Boolean = {
    (property, tStart, tEnd, vMin, vMax) match {
      case (Property(mapExpr, propertyKey), ParameterExpression(start), ParameterExpression(end), ParameterExpression(vmin), ParameterExpression(vmax)) =>
        val s = state.getParam(start)
        val e = state.getParam(end)
        val vvmin = state.getParam(vmin)
        val vvmax = state.getParam(vmax)
        (mapExpr(ctx), s, e, vvmin, vvmax) match {
          case (r: Relationship, start:Long, end:Long, v_min:Long, v_max:Long) =>
            val propId = propertyKey.getOrCreateId(state.query)
            temporalPropertyValueWithinExist(state.query.relationshipOps, r.getId, propId, start, end, v_min, v_max)
        }
    }
  }

  private def temporalPropertyValueWithinExist(ops: Operations[Relationship], rId: Long, pId:Int, s:Long, e:Long, vmin:Long, vmax:Long): Boolean = {
    ops.getTemporalPropertyValueWithin(rId, pId, new TP(s), new TP(e), vmin.toInt, vmax.toInt)
  }

  override protected def calculateType(symbols: SymbolTable): CypherType = CTBoolean

  override def toString: String = "TPWithinExistFunc("+property.toString()+")"

  override def symbolTableDependencies: Set[String] = Set()
}

case class TemporalAggregationMaxFunction(property: Expression, tStart: Expression, tEnd:Expression) extends Expression{
  override def rewrite(f: (Expression) => Expression): Expression = this

  override def arguments: Seq[Expression] = Seq(property, tStart, tEnd)

  override def apply(ctx: ExecutionContext)(implicit state: QueryState): Any = {
    (property, tStart, tEnd) match {
      case (Property(mapExpr, propertyKey), start: ParameterExpression, end: ParameterExpression) =>
        val s = state.getParam(start.parameterName)
        val e = state.getParam(end.parameterName)
        (mapExpr(ctx), s, e) match {
          case (n: Node, start:Long, end:Long) =>
            val propId = propertyKey.getOrCreateId(state.query)
            temporalPropertyValueMatch(state.query.nodeOps, n.getId, propId, start, end)
          case (r: Relationship, start:Long, end:Long) => temporalPropertyValueMatch(state.query.relationshipOps, r.getId, propertyKey.getOrCreateId(state.query), start, end)
        }
    }
  }

  def temporalPropertyValueMatch[T <: PropertyContainer](op: Operations[T], entityId: Long, propertyId: Int, start: Long, end: Long): Any = {
    var min:Int = Int.MaxValue
    for (t <- start.toInt to end.toInt){
      val value = op.getTemporalProperty(entityId, propertyId, new org.neo4j.temporal.TimePoint(t))
      value match {
        case v: Int => if (min > v) min = v
        case v: Long => if (min > v) min = v.toInt
        case null => //do nothing if invalid (no value)
      }
    }
    if(min==Int.MaxValue) null
    else min
  }

  override protected def calculateType(symbols: SymbolTable): CypherType = CTInteger

  override def toString: String = "TPAggrMaxFunc("+property.toString()+")"

  override def symbolTableDependencies: Set[String] = Set()
}


case class SnapshotValueFunction(property: Expression, tVal: Expression) extends Expression{
  override def rewrite(f: (Expression) => Expression): Expression = this

  override def arguments: Seq[Expression] = Seq(property, tVal)

  def temporalPropertyValue[T <: PropertyContainer](ops: Operations[T], entityId: Long, propId: Int, time: Long): Any = {
    val v = ops.getTemporalProperty(entityId, propId, new org.neo4j.temporal.TimePoint(time.toInt))
    v
  }

  override def apply(ctx: ExecutionContext)(implicit state: QueryState): Any = {
    (property, tVal) match {
      case (Property(mapExpr, propertyKey), time: ParameterExpression) =>
        val t = state.getParam(time.parameterName)
        (mapExpr(ctx), t) match {
          case (n: Node, time:Long) =>
            val propId = propertyKey.getOrCreateId(state.query)
            temporalPropertyValue(state.query.nodeOps, n.getId, propId, time)
          case (r: Relationship, time:Long) => temporalPropertyValue(state.query.relationshipOps, r.getId, propertyKey.getOrCreateId(state.query), time)
        }
    }
  }

  override protected def calculateType(symbols: SymbolTable): CypherType = CTInteger

  override def toString: String = "TPValueAtFunc("+property.toString()+")"

  override def symbolTableDependencies: Set[String] = Set()
}


