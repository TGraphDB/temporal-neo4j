package org.neo4j.cypher.internal.frontend.v2_3.ast.functions

import org.neo4j.cypher.internal.frontend.v2_3.{SemanticCheck, SemanticCheckResult, SemanticError}
import org.neo4j.cypher.internal.frontend.v2_3.ast.{Function, FunctionInvocation, Parameter, PatternExpression, Property, PropertyKeyName, SignedDecimalIntegerLiteral, SignedIntegerLiteral, SimpleTypedFunction, StringLiteral, UnsignedIntegerLiteral}
import org.neo4j.cypher.internal.frontend.v2_3.ast.Expression.SemanticContext
import org.neo4j.cypher.internal.frontend.v2_3.symbols._

/**
  * Created by song on 2018-12-11.
  */
case object ValueAtFunc extends Function with SimpleTypedFunction  {
  override def name: String = "tp_value_at"

  override val signatures: Seq[ValueAtFunc.Signature] = Vector(
    Signature(argumentTypes = Vector(CTTValue, CTInteger), outputType = CTAny)
  )
}

case object TemporalAggregationMinFunc extends Function{
  override def name: String = "tp_min"

  // modified from org.neo4j.cypher.internal.frontend.v2_3.ast.functions.Exist class.
  override def semanticCheck(ctx: SemanticContext, invocation: FunctionInvocation): SemanticCheck = {
    checkArgs(invocation, 3) ifOkChain {
      val tmp = invocation.arguments // same as invocation.args, but former is a list :: later is a Vector.
      tmp.head.expectType(CTAny.covariant) chain invocation.specifyType(CTTValue) chain {
        (tmp.head, tmp(1), tmp(2)) match {
          case (_: Property, s:SignedDecimalIntegerLiteral, e:SignedDecimalIntegerLiteral) =>
            if(0 < s.value)
              if(s.value <= e.value ) None else Some(SemanticError("start time must less or equal than end time.", s.position, e.position))
            else Some(SemanticError("Time must be larger than 0.", s.position, e.position))
          case (_: Property, _: Parameter, _: Parameter) => None // don't known why this could happen (semanticCheck called twice), weird.
          case (p, _, _) =>
            Some(SemanticError(s"First argument to ${invocation.name}(...) is not a property",  p.position, invocation.position))
        }
      }
    }
  }
}

case object TemporalAggregationMaxFunc extends Function{
  override def name: String = "tp_max"

  // modified from org.neo4j.cypher.internal.frontend.v2_3.ast.functions.Exist class.
  override def semanticCheck(ctx: SemanticContext, invocation: FunctionInvocation): SemanticCheck = {
    checkArgs(invocation, 3) ifOkChain {
      val tmp = invocation.arguments // same as invocation.args, but former is a list :: later is a Vector.
      tmp.head.expectType(CTAny.covariant) chain invocation.specifyType(CTTValue) chain {
        (tmp.head, tmp(1), tmp(2)) match {
          case (_: Property, s:SignedDecimalIntegerLiteral, e:SignedDecimalIntegerLiteral) =>
            if(0 < s.value)
              if(s.value <= e.value ) None else Some(SemanticError("start time must less or equal than end time.", s.position, e.position))
            else Some(SemanticError("Time must be larger than 0.", s.position, e.position))
          case (_: Property, _: Parameter, _: Parameter) => None // don't known why this could happen (semanticCheck called twice), weird.
          case (p, _, _) =>
            Some(SemanticError(s"First argument to ${invocation.name}(...) is not a property",  p.position, invocation.position))
        }
      }
    }
  }
}

case object TemporalAggregationDurationFunc extends Function{
  override def name: String = "tp_duration"

  // modified from org.neo4j.cypher.internal.frontend.v2_3.ast.functions.Exist class.
  override def semanticCheck(ctx: SemanticContext, invocation: FunctionInvocation): SemanticCheck = {
    checkArgs(invocation, 3) ifOkChain {
      val tmp = invocation.arguments // same as invocation.args, but former is a list :: later is a Vector.
      tmp.head.expectType(CTAny.covariant) chain invocation.specifyType(CTTValue) chain {
        (tmp.head, tmp(1), tmp(2)) match {
          case (_: Property, s:SignedDecimalIntegerLiteral, e:SignedDecimalIntegerLiteral) =>
            if(0 < s.value)
              if(s.value <= e.value ) None else Some(SemanticError("start time must less or equal than end time.", s.position, e.position))
            else Some(SemanticError("Time must be larger than 0.", s.position, e.position))
          case (_: Property, _: Parameter, _: Parameter) => None // don't known why this could happen (semanticCheck called twice), weird.
          case (p, _, _) =>
            Some(SemanticError(s"First argument to ${invocation.name}(...) is not a property",  p.position, invocation.position))
        }
      }
    }
  }
}

case object TPWithinAlwaysFunc extends Function with SimpleTypedFunction  {
  override def name: String = "tp_within_always"

  override val signatures: Seq[TPWithinAlwaysFunc.Signature] = Vector(
    Signature(argumentTypes = Vector(CTAny, CTTValue), outputType = CTBoolean)
  )
}

//MATCH ()-[r:ROAD_TO]->() WHERE tp_within_exists(r.travel_time, 201005020800, 201005021200, 100, 200) RETURN r.name
case object TPWithinExistsFunc extends Function {
  override def name: String = "tp_within_exists"
  var startTime:Long = Long.MinValue //此处进行了一个hack，因为在plan阶段是拿不到函数的调用参数的，但我需要时间参数来决定是否用索引Operator
  var endTime:Long = Long.MinValue //警告：这个hack导致的结果是只能允许在查询中出现一个该函数的调用，超过一个会导致不可预料的结果。原因是这是个object，多个函数调用都会对应到这个instance。

  override def semanticCheck(ctx: SemanticContext, invocation: FunctionInvocation): SemanticCheck = {
    checkArgs(invocation, 5) ifOkChain {
      val tmp = invocation.arguments // same as invocation.args, but former is a list :: later is a Vector.
      tmp.head.expectType(CTAny.covariant) chain invocation.specifyType(CTBoolean) chain {
        (tmp.head, tmp(1), tmp(2), tmp(3), tmp(4)) match {
          case (_: Property, s:SignedDecimalIntegerLiteral, e:SignedDecimalIntegerLiteral, _, _) =>
            if(0 < s.value) {
              startTime = s.value
              endTime = e.value
              if(s.value <= e.value ) None else Some(SemanticError("start time must less or equal than end time.", s.position, e.position))
            } else Some(SemanticError("Time must be larger than 0.", s.position, e.position))
          case (_: Property, _: Parameter, _: Parameter, _, _) => None // don't known why this could happen (semanticCheck called twice), weird.
          case (p, _, _, _, _) =>
            Some(SemanticError(s"First argument to ${invocation.name}(...) is not a property",  p.position, invocation.position))
        }
      }
    }
  }
}


case object TemporalProjectionFunc extends Function with SimpleTypedFunction  {
  override def name: String = "tp"

  override val signatures: Seq[TemporalProjectionFunc.Signature] = Vector(
    Signature(argumentTypes = Vector(CTTValue, CTTimeRange), outputType = CTTValue)
  )
}





