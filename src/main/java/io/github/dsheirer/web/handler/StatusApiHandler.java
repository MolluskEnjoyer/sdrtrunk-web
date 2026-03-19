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
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * REST API handler for system status overview.
 *
 * Endpoint:
 *   GET /api/status - Get overall system status (tuners, channels, decoder state)
 */
public class StatusApiHandler extends BaseApiHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(StatusApiHandler.class);

    private final PlaylistManager mPlaylistManager;
    private final TunerManager mTunerManager;
    private final DecoderApiHandler mDecoderHandler;

    public StatusApiHandler(PlaylistManager playlistManager, TunerManager tunerManager,
                           DecoderApiHandler decoderHandler)
    {
        mPlaylistManager = playlistManager;
        mTunerManager = tunerManager;
        mDecoderHandler = decoderHandler;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException
    {
        if(!"GET".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        JsonObject status = new JsonObject();

        // Tuner count
        int tunerCount = 0;
        for(DiscoveredTuner dt : mTunerManager.getAvailableTuners())
        {
            tunerCount++;
        }
        status.addProperty("tuner_count", tunerCount);

        // Processing status
        boolean processing = mPlaylistManager.getChannelProcessingManager().isProcessing();
        status.addProperty("processing", processing);

        // Web decoder status
        status.addProperty("web_decoder_running", mDecoderHandler.isDecoderRunning());

        Channel webChannel = mDecoderHandler.getWebChannel();
        if(webChannel != null)
        {
            status.addProperty("web_channel", webChannel.getName());
        }

        // Channel count from model
        int channelCount = mPlaylistManager.getChannelModel().getChannels().size();
        status.addProperty("channel_count", channelCount);

        sendJson(exchange, 200, status);
    }
}
