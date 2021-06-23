/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_3.pipes

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import org.apache.commons.math3.stat.regression.{OLSMultipleLinearRegression, SimpleRegression}
import org.neo4j.cypher.internal.compatibility.ExceptionTranslatingQueryContextFor2_3
import org.neo4j.cypher.internal.compiler.v2_3.commands.SingleQueryExpression
import org.neo4j.cypher.internal.compiler.v2_3.commands.expressions.{IdFunction, Identifier, Literal, ParameterExpression, Property, TPWithinExistFunction, TemporalAggregationMaxFunction}
import org.neo4j.cypher.internal.compiler.v2_3.commands.predicates.{CoercedPredicate, LessThan, Predicate}
import org.neo4j.cypher.internal.compiler.v2_3.commands.values.{KeyToken, TokenType}
import org.neo4j.cypher.internal.compiler.v2_3.planner.act.RelIdSeekByTemporalIndexPipe
import org.neo4j.cypher.internal.frontend.v2_3.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.frontend.v2_3.{LabelId, PropertyKeyId, SemanticDirection, ast}
import org.neo4j.cypher.internal.spi.v2_3.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.spi.v2_3.{TransactionBoundPlanContext, TransactionBoundQueryContext}
import org.neo4j.graphdb._
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.neo4j.kernel.GraphDatabaseAPI
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Estimates values used by CardinalityCostModel, note that this takes at least on the order
 * of a couple of minutes to finish.
 */
class FilterWithTemporalFunctionCostCalculationTest extends CypherFunSuite {

  implicit val monitor = new PipeMonitor {
    def stopStep(queryId: AnyRef, pipe: Pipe) {}
    def stopSetup(queryId: AnyRef, pipe: Pipe) {}
    def startSetup(queryId: AnyRef, pipe: Pipe) {}
    def startStep(queryId: AnyRef, pipe: Pipe) {}
  }
  private val N = 100000
  private val STEPS = 100
  private val LABEL = DynamicLabel.label("L")


  test("do the test") {
    val graph: GraphDatabaseService = new GraphDatabaseFactory().newEmbeddedDatabase(new File("D:/tgraph/testdb"))
    try {
      val results = ResultTable.empty
//      results.addAll("AllNodeScan", runSimulation(graph, Seq(allNodes)))
      results.addAll("RelIdSeekByTemporalIndex", runTemporalSimulation(graph, temporalAggregation()))
      results.normalizedResult.foreach {
        case (name, slope) => println(s"$name: COST = $slope * TIME_LEN")
      }
    } finally {
      graph.shutdown()
    }
  }

  class ResultTable {
    private val table = mutable.HashMap.empty[String, ListBuffer[DataPoint]]

    def foreach(f: ((String, Seq[DataPoint])) => Unit) = table.foreach(f)
    def add(name: String, dataPoint: DataPoint) =
      table.getOrElseUpdate(name, ListBuffer.empty).append(dataPoint)

    def addAll(name: String, dataPoints: Seq[DataPoint]) =
      table.getOrElseUpdate(name, ListBuffer.empty).appendAll(dataPoints)

    def normalizedResult = {
      val result = table.mapValues(calculateSimpleResult)
      val minValue = result.values.min
      result.mapValues(_/minValue)
    }

    override def toString: String = table.map{
      case (name, dataPoints) => s"$name: $dataPoints"
    }.mkString("\n")
  }

  object ResultTable {
    def empty = new ResultTable
    def apply() = new ResultTable
  }

  case class DataPoint(elapsed: Double, numberOfRows: Long) {
    def subtractTime(subtract: Double) = copy(elapsed = elapsed - subtract)

    override def toString: String = s"$numberOfRows, $elapsed"
  }

  //From the provided data points, estimate slope and intercept in `cost = slope*NROWS + intercept`
  private def calculateSimpleResult(dataPoints: Seq[DataPoint]): Double= {
    if (dataPoints.isEmpty) throw new IllegalArgumentException("Cannot compute result without any data points")
    else if (dataPoints.size == 1) {
      val dp = dataPoints.head
      dp.elapsed / dp.numberOfRows.toDouble
    } else {
      val regression = new SimpleRegression(false)
      dataPoints.foreach(dp => regression.addData(dp.numberOfRows, dp.elapsed))
      regression.getSlope
    }
  }

  //executes the provided pipes and returns execution times
  private def runSimulation(graph: GraphDatabaseService, pipes: Seq[Pipe]) = {
    val results = new ListBuffer[DataPoint]
    graph.withTx { tx =>
      val resources: ExternalResource = mock[ExternalResource]
      val queryContext = new TransactionBoundQueryContext(graph.asInstanceOf[GraphDatabaseAPI], tx, true, graph.statement)(mock[IndexSearchMonitor])
      val state = new QueryState(queryContext, resources, params = Map.empty, decorator = NullPipeDecorator)
      for (x <- 0 to 12) {
        for (pipe <- pipes) {
          val start = System.currentTimeMillis()
          val numberOfRows = pipe.createResults(state).size
          val elapsed = System.currentTimeMillis() - start

          //warmup
          if (x > 2) results.append(DataPoint(elapsed, numberOfRows))
        }
      }
    }
    //remove fastest and slowest
    results.sortBy(_.elapsed).slice(5, results.size - 5)
  }

  private def runTemporalSimulation(graph: GraphDatabaseService, params: Seq[(Long, Long, Long)]) = {
    val results = new ListBuffer[DataPoint]
    graph.withTx { tx =>
      val resources: ExternalResource = mock[ExternalResource]
      val queryContext = new TransactionBoundQueryContext(graph.asInstanceOf[GraphDatabaseAPI], tx, true, graph.statement)(mock[IndexSearchMonitor])
      for (param <- params) {
        val state = new QueryState(queryContext, resources, params = Map("param_rid"->param._1, "param_ts"-> param._2, "param_te"-> param._3), decorator = NullPipeDecorator)
        val timeIntervalSize = param._3 - param._2
        for (x <- 0 to 12) {
          val pipe = temporalAggregationProjectionPipe(graph)
          val start = System.currentTimeMillis()
          val s = pipe.createResults(state).next()
          val elapsed = System.currentTimeMillis() - start
          println(elapsed+","+timeIntervalSize+","+s)
          //warmup
          if (x > 2) results.append(DataPoint(elapsed, timeIntervalSize))
        }
      }
    }
    //remove fastest and slowest
    results.sortBy(_.elapsed).slice(5, results.size - 5)
  }

  private def expand(l: Pipe, t: String) = new ExpandAllPipe(l, "x", "r", "n", SemanticDirection.OUTGOING, LazyTypes(Seq(t)))()

  private def allNodes = new AllNodesScanPipe("x")()

  private def temporalAggregationProjectionPipe(graph: GraphDatabaseService) = {
    graph.withTx { _ =>
      ProjectionPipe(
        DirectedRelationshipByIdSeekPipe("r", SingleSeekArg(ParameterExpression("param_rid")), "b", "a")(),
        Map("tp_max()" -> TemporalAggregationMaxFunction(
            Property(Identifier("r"), KeyToken.Unresolved("travel_time", TokenType.PropertyKey)),
            ParameterExpression("param_ts"),
            ParameterExpression("param_te")
          ))
      )()
    }
  }

  private def temporalAggregation() = {
    Seq(
//      (1L, 1272762000L, 1272769200L),//5.2 9~11
//      (3L, 1272762000L, 1272776400L),//5.2 9~13
//      (4L, 1272762000L, 1272783600L),//5.2 9~15
//      (5L, 1272762000L, 1272790800L),//5.2 9~17
//      (6L, 1272762000L, 1272798000L), //5.2 9~19
      (1L, 1272762000L, 1272805200L),//5.2 9~21:00
      (3L, 1272762000L, 1272891600L),//5.3 21:00
      (4L, 1272762000L, 1272978000L),//5.4 21:00
      (5L, 1272762000L, 1273064400L),//5.5 21:00
      (6L, 1272762000L, 1273150800L) //5.6 21:00
    )
  }

  implicit class RichGraph(graph: GraphDatabaseService) {
    def statement = graph.asInstanceOf[GraphDatabaseAPI].getDependencyResolver.resolveDependency(classOf[ThreadToStatementContextBridge]).get()

    def withTx[T](f: Transaction => T): T = {
      val tx = graph.beginTx()
      try {
        val result = f(tx)
        tx.success()
        result
      } finally {
        tx.close()
      }
    }

    def createIndex(label: Label, propertyName: String) = {
      graph.withTx { _ =>
        graph.schema().indexFor(label).on(propertyName).create()
      }

      graph.withTx { _ =>
        graph.schema().awaitIndexesOnline(10, TimeUnit.SECONDS)
      }
    }
  }
}

