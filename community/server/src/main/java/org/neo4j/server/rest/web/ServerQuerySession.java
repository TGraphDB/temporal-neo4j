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
package org.neo4j.server.rest.web;

import javax.servlet.http.HttpServletRequest;

import org.neo4j.kernel.impl.query.QuerySession;

public class ServerQuerySession extends QuerySession
{
    private final HttpServletRequest request;

    public ServerQuerySession( HttpServletRequest request )
    {
        this.request = request;
    }

    @Override
    public String toString()
    {
        return request != null ? String.format( "server-session\t%s\t%s", request.getRemoteAddr(),
                request.getRequestURI() ) : "server-session";
    }
}
