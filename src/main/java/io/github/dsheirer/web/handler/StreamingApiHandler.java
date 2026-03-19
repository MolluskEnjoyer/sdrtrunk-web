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
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.ConfiguredBroadcast;
import io.github.dsheirer.playlist.PlaylistManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * REST API handler for streaming/broadcast status.
 *
 * Endpoint:
 *   GET /api/streaming/status - Returns status of all configured broadcast streams
 */
public class StreamingApiHandler extends BaseApiHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(StreamingApiHandler.class);

    private final PlaylistManager mPlaylistManager;

    public StreamingApiHandler(PlaylistManager playlistManager)
    {
        mPlaylistManager = playlistManager;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException
    {
        if(!"GET".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        BroadcastModel model = mPlaylistManager.getBroadcastModel();

        JsonArray streams = new JsonArray();

        for(ConfiguredBroadcast cb : model.getConfiguredBroadcasts())
        {
            JsonObject s = new JsonObject();

            s.addProperty("name", cb.getBroadcastConfiguration().getName());
            s.addProperty("server_type", cb.getBroadcastServerType().toString());
            s.addProperty("enabled", cb.getBroadcastConfiguration().isEnabled());

            // Determine state
            BroadcastState state;
            if(cb.hasAudioBroadcaster())
            {
                state = cb.getAudioBroadcaster().getBroadcastState();
            }
            else if(!cb.getBroadcastConfiguration().isEnabled())
            {
                state = BroadcastState.DISABLED;
            }
            else if(!cb.getBroadcastConfiguration().isValid())
            {
                state = BroadcastState.INVALID_SETTINGS;
            }
            else
            {
                state = BroadcastState.ERROR;
            }

            s.addProperty("state", state.toString());
            s.addProperty("error_state", state.isErrorState());

            // Counters from the broadcaster
            if(cb.hasAudioBroadcaster())
            {
                AbstractAudioBroadcaster<?> broadcaster = cb.getAudioBroadcaster();
                s.addProperty("queue_size", broadcaster.getAudioQueueSize());
                s.addProperty("streamed_count", broadcaster.getStreamedAudioCount());
                s.addProperty("aged_off_count", broadcaster.getAgedOffAudioCount());
                s.addProperty("error_count", broadcaster.getAudioErrorCount());
            }
            else
            {
                s.addProperty("queue_size", 0);
                s.addProperty("streamed_count", 0);
                s.addProperty("aged_off_count", 0);
                s.addProperty("error_count", 0);
            }

            streams.add(s);
        }

        JsonObject result = new JsonObject();
        result.add("streams", streams);
        sendJson(exchange, 200, result);
    }
}
