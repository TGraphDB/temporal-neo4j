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

case object TPWithinExistsFunc extends Function with SimpleTypedFunction  {
  override def name: String = "tp_within_exists"

  override val signatures: Seq[TPWithinExistsFunc.Signature] = Vector(
    Signature(argumentTypes = Vector(CTTValue, CTInteger, CTInteger, CTInteger, CTInteger), outputType = CTBoolean)
  )
}


case object TemporalProjectionFunc extends Function with SimpleTypedFunction  {
  override def name: String = "tp"

  override val signatures: Seq[TemporalProjectionFunc.Signature] = Vector(
    Signature(argumentTypes = Vector(CTTValue, CTTimeRange), outputType = CTTValue)
  )
}





