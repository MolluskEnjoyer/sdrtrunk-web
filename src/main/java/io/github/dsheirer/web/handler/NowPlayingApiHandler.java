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
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.playlist.PlaylistManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * REST API handler for "Now Playing" channel metadata.
 *
 * Endpoint:
 *   GET /api/channels/now-playing - Returns all active channel metadata rows
 */
public class NowPlayingApiHandler extends BaseApiHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(NowPlayingApiHandler.class);

    private final PlaylistManager mPlaylistManager;

    public NowPlayingApiHandler(PlaylistManager playlistManager)
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

        ChannelMetadataModel model = mPlaylistManager.getChannelProcessingManager().getChannelMetadataModel();

        JsonArray channels = new JsonArray();

        int rowCount = model.getRowCount();
        for(int i = 0; i < rowCount; i++)
        {
            ChannelMetadata meta = model.getChannelMetadata(i);
            if(meta == null)
            {
                continue;
            }

            Channel channel = model.getChannelFromMetadata(meta);

            JsonObject ch = new JsonObject();

            // State: ACTIVE, CALL, CONTROL, IDLE, etc.
            ch.addProperty("state", meta.getChannelStateIdentifier() != null
                ? meta.getChannelStateIdentifier().toString() : "UNKNOWN");

            // Decoder type
            ch.addProperty("decoder", meta.hasDecoderTypeIdentifier()
                ? meta.getDecoderTypeConfigurationIdentifier().toString() : "");

            // From (radio/subscriber ID)
            ch.addProperty("from", meta.hasFromIdentifier()
                ? meta.getFromIdentifier().toString() : "");

            // From alias
            ch.addProperty("from_alias", aliasListToString(meta.getFromIdentifierAliases()));

            // To (talkgroup)
            ch.addProperty("to", meta.hasToIdentifier()
                ? meta.getToIdentifier().toString() : "");

            // To alias
            ch.addProperty("to_alias", aliasListToString(meta.getToIdentifierAliases()));

            // Logical channel name (e.g. "3-817")
            ch.addProperty("channel", meta.hasDecoderLogicalChannelNameIdentifier()
                ? meta.getDecoderLogicalChannelNameIdentifier().getValue() : "");

            // Frequency in Hz
            if(meta.hasFrequencyConfigurationIdentifier())
            {
                ch.addProperty("frequency", meta.getFrequencyConfigurationIdentifier().getValue());
            }
            else
            {
                ch.addProperty("frequency", (Long) null);
            }

            // Channel name from configuration
            ch.addProperty("channel_name", meta.hasChannelConfigurationIdentifier()
                ? meta.getChannelNameConfigurationIdentifier().toString() : "");

            channels.add(ch);
        }

        JsonObject result = new JsonObject();
        result.add("channels", channels);
        sendJson(exchange, 200, result);
    }

    private String aliasListToString(List<Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < aliases.size(); i++)
        {
            if(i > 0)
            {
                sb.append(", ");
            }
            sb.append(aliases.get(i).getName());
        }
        return sb.toString();
    }
}
