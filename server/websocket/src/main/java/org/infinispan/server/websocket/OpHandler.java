/*
 * JBoss, Home of Professional Open Source
 * Copyright 2006, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.server.websocket;

import org.infinispan.Cache;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Websocket cache operation handler.
 * 
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public interface OpHandler {
	
	public static final String OP_CODE = "opCode";
	public static final String CACHE_NAME = "cacheName";
	public static final String KEY = "key";
	public static final String VALUE = "value";
	public static final String MIME = "mime";

	/**
	 * Handle a websocket channel operation.
	 * 
	 * @param opPayload Operation payload.
	 * @param cache The target cache.
	 * @param ctx The Netty websocket channel handler.
	 */
	void handleOp(JSONObject opPayload, Cache<Object, Object> cache, ChannelHandlerContext ctx) throws JSONException;
}
