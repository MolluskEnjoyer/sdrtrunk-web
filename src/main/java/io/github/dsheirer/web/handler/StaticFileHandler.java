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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serves the web UI static files from the classpath (src/main/resources/web/).
 * Handles /, /index.html, and other static assets.
 */
public class StaticFileHandler implements HttpHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(StaticFileHandler.class);
    private static final String RESOURCE_BASE = "/web/";

    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        String path = exchange.getRequestURI().getPath();

        // Default to index.html
        if("/".equals(path) || path.isEmpty())
        {
            path = "/index.html";
        }

        // Prevent path traversal
        if(path.contains(".."))
        {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        String resourcePath = RESOURCE_BASE + path.substring(1); // Remove leading /

        try(InputStream is = getClass().getResourceAsStream(resourcePath))
        {
            if(is == null)
            {
                // If file not found but it's not an API path, serve index.html (SPA fallback)
                if(!path.startsWith("/api/"))
                {
                    try(InputStream fallback = getClass().getResourceAsStream(RESOURCE_BASE + "index.html"))
                    {
                        if(fallback != null)
                        {
                            serveStream(exchange, fallback, "text/html");
                            return;
                        }
                    }
                }
                String error = "Not found: " + path;
                exchange.sendResponseHeaders(404, error.length());
                try(OutputStream os = exchange.getResponseBody())
                {
                    os.write(error.getBytes());
                }
                return;
            }

            String contentType = getMimeType(path);
            serveStream(exchange, is, contentType);
        }
    }

    private void serveStream(HttpExchange exchange, InputStream is, String contentType) throws IOException
    {
        byte[] content = is.readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, content.length);
        try(OutputStream os = exchange.getResponseBody())
        {
            os.write(content);
        }
    }

    private String getMimeType(String path)
    {
        if(path.endsWith(".html")) return "text/html; charset=utf-8";
        if(path.endsWith(".css")) return "text/css; charset=utf-8";
        if(path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if(path.endsWith(".json")) return "application/json; charset=utf-8";
        if(path.endsWith(".png")) return "image/png";
        if(path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if(path.endsWith(".svg")) return "image/svg+xml";
        if(path.endsWith(".ico")) return "image/x-icon";
        if(path.endsWith(".woff2")) return "font/woff2";
        if(path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }
}
