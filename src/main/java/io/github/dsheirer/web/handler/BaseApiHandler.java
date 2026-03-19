/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.web.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Base handler with utility methods for JSON responses and request parsing.
 */
public abstract class BaseApiHandler implements HttpHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(BaseApiHandler.class);
    protected static final Gson GSON = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        // CORS headers for browser access
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try
        {
            handleRequest(exchange);
        }
        catch(Exception e)
        {
            mLog.error("Error handling request: {}", exchange.getRequestURI(), e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    protected abstract void handleRequest(HttpExchange exchange) throws IOException;

    protected void sendJson(HttpExchange exchange, int status, Object obj) throws IOException
    {
        String json = GSON.toJson(obj);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try(OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }

    protected void sendError(HttpExchange exchange, int status, String message) throws IOException
    {
        JsonObject error = new JsonObject();
        error.addProperty("error", message != null ? message : "Unknown error");
        sendJson(exchange, status, error);
    }

    protected String readBody(HttpExchange exchange) throws IOException
    {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    protected JsonObject readJsonBody(HttpExchange exchange) throws IOException
    {
        String body = readBody(exchange);
        return GSON.fromJson(body, JsonObject.class);
    }

    protected Map<String, String> parseQuery(HttpExchange exchange)
    {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        if(query != null)
        {
            for(String param : query.split("&"))
            {
                String[] pair = param.split("=", 2);
                if(pair.length == 2)
                {
                    params.put(
                        URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                    );
                }
            }
        }
        return params;
    }

    /**
     * Extract the sub-path from the request URI after the context prefix.
     * For example, if context is "/api/rr/" and URI is "/api/rr/login", returns "login".
     */
    protected String getSubPath(HttpExchange exchange, String contextPrefix)
    {
        String path = exchange.getRequestURI().getPath();
        if(path.startsWith(contextPrefix))
        {
            return path.substring(contextPrefix.length());
        }
        return path;
    }
}
