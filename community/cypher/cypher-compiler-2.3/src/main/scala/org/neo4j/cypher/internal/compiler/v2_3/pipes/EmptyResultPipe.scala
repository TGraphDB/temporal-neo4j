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

import org.neo4j.cypher.internal.compiler.v2_3._
import org.neo4j.cypher.internal.compiler.v2_3.executionplan.Effects
import org.neo4j.cypher.internal.compiler.v2_3.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v2_3.symbols._

case class EmptyResultPipe(source: Pipe)(implicit pipeMonitor: PipeMonitor) extends PipeWithSource(source, pipeMonitor) {

  protected def internalCreateResults(input:Iterator[ExecutionContext], state: QueryState) = {
    while(input.hasNext) {
      input.next()
    }

    Iterator.empty
  }

  override def planDescription = source.planDescription.andThen(this.id, "EmptyResult", identifiers)

  def symbols = SymbolTable()

  // this pipe has no effects
  override val localEffects = Effects()
  override val effects = Effects()

  def dup(sources: List[Pipe]): Pipe = {
    val (source :: Nil) = sources
    copy(source = source)
  }
}
