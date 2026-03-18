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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * REST API handler for tuner information.
 *
 * Endpoint:
 *   GET /api/tuners - List available tuners and their status
 */
public class TunerApiHandler extends BaseApiHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerApiHandler.class);

    private final TunerManager mTunerManager;

    public TunerApiHandler(TunerManager tunerManager)
    {
        mTunerManager = tunerManager;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException
    {
        if(!"GET".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        JsonArray tuners = new JsonArray();

        for(DiscoveredTuner dt : mTunerManager.getAvailableTuners())
        {
            JsonObject tunerObj = new JsonObject();
            if(dt.getTuner() != null)
            {
                tunerObj.addProperty("name", dt.getTuner().getPreferredName());
                tunerObj.addProperty("status", "available");
            }
            else
            {
                tunerObj.addProperty("name", "Unknown");
                tunerObj.addProperty("status", "unavailable");
            }
            tuners.add(tunerObj);
        }

        JsonObject result = new JsonObject();
        result.add("tuners", tuners);
        result.addProperty("count", tuners.size());
        sendJson(exchange, 200, result);
    }
}
