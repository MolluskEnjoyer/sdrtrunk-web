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
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * REST API handler for decoder control.
 * Bridges the web frontend to SDRTrunk's ChannelProcessingManager.
 *
 * Endpoints:
 *   POST /api/decoder/start  - Start a P25 channel (JSON body with control_freq, talkgroups, etc.)
 *   POST /api/decoder/stop   - Stop the active web-started channel
 *   GET  /api/decoder/status - Get decoder status, active call info, and activity log
 */
public class DecoderApiHandler extends BaseApiHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(DecoderApiHandler.class);
    private static final String CONTEXT_PREFIX = "/api/decoder/";
    private static final int MAX_LOG_LINES = 200;

    private final PlaylistManager mPlaylistManager;
    private final TunerManager mTunerManager;
    private final UserPreferences mUserPreferences;

    // Track the channel we started from the web UI
    private Channel mWebChannel;
    private boolean mDecoderRunning;

    // Activity log ring buffer
    private final List<String> mLogLines = Collections.synchronizedList(new ArrayList<>());
    private int mLogReadPos = 0;

    public DecoderApiHandler(PlaylistManager playlistManager, TunerManager tunerManager,
                             UserPreferences userPreferences)
    {
        mPlaylistManager = playlistManager;
        mTunerManager = tunerManager;
        mUserPreferences = userPreferences;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException
    {
        String subPath = getSubPath(exchange, CONTEXT_PREFIX);
        String method = exchange.getRequestMethod().toUpperCase();

        switch(subPath)
        {
            case "start":
                if("POST".equals(method)) handleStart(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "stop":
                if("POST".equals(method)) handleStop(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "status":
                if("GET".equals(method)) handleStatus(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            default:
                sendError(exchange, 404, "Not found: " + subPath);
        }
    }

    private void handleStart(HttpExchange exchange) throws IOException
    {
        if(mDecoderRunning && mWebChannel != null)
        {
            sendError(exchange, 409, "Decoder already running. Stop it first.");
            return;
        }

        JsonObject body = readJsonBody(exchange);

        String controlFreqStr = body.has("control_freq") ? body.get("control_freq").getAsString() : null;
        if(controlFreqStr == null || controlFreqStr.isEmpty())
        {
            sendError(exchange, 400, "control_freq required");
            return;
        }

        long controlFreq;
        try
        {
            controlFreq = Long.parseLong(controlFreqStr);
        }
        catch(NumberFormatException e)
        {
            sendError(exchange, 400, "Invalid control_freq format");
            return;
        }

        // Parse talkgroup list
        List<Integer> talkgroups = new ArrayList<>();
        if(body.has("talkgroups") && body.get("talkgroups").isJsonArray())
        {
            for(var tg : body.get("talkgroups").getAsJsonArray())
            {
                talkgroups.add(tg.getAsInt());
            }
        }

        boolean followAll = body.has("follow_all") && body.get("follow_all").getAsBoolean();

        if(talkgroups.isEmpty() && !followAll)
        {
            sendError(exchange, 400, "Select talkgroups or enable follow_all");
            return;
        }

        try
        {
            // Create a P25 Phase 1 trunking channel (control channel is always Phase 1)
            Channel channel = new Channel("Web P25 Scanner");
            channel.setSystem("Web Scanner");
            channel.setSite("Web");

            // Configure P25 Phase 1 decoder for the control channel
            DecodeConfigP25Phase1 decodeConfig = new DecodeConfigP25Phase1();
            channel.setDecodeConfiguration(decodeConfig);

            // Configure source with control frequency
            SourceConfigTuner sourceConfig = new SourceConfigTuner();
            sourceConfig.setFrequency(controlFreq);
            channel.setSourceConfiguration(sourceConfig);

            // Set up alias list for selected talkgroups
            String aliasListName = "Web Scanner TGs";
            channel.setAliasListName(aliasListName);

            // Create aliases for selected talkgroups
            AliasModel aliasModel = mPlaylistManager.getAliasModel();
            for(int tgDec : talkgroups)
            {
                Alias alias = new Alias("TG " + tgDec);
                alias.setAliasListName(aliasListName);
                Talkgroup tgId = new Talkgroup(Protocol.APCO25, tgDec);
                alias.addAliasID(tgId);
                aliasModel.addAlias(alias);
            }

            // Add to channel model and start
            mPlaylistManager.getChannelModel().addChannel(channel);

            ChannelProcessingManager cpm = mPlaylistManager.getChannelProcessingManager();
            cpm.start(channel);

            mWebChannel = channel;
            mDecoderRunning = true;
            mLogLines.clear();
            mLogReadPos = 0;

            addLog("Decoder started - Control: " + (controlFreq / 1e6) + " MHz, TGs: " +
                (followAll ? "ALL" : talkgroups.size() + " selected"));

            JsonObject result = new JsonObject();
            result.addProperty("status", "started");
            result.addProperty("control_freq", controlFreq);
            sendJson(exchange, 200, result);

            mLog.info("Web UI: Decoder started on {} MHz with {} talkgroups",
                controlFreq / 1e6, followAll ? "all" : talkgroups.size());
        }
        catch(ChannelException e)
        {
            mLog.error("Failed to start decoder channel", e);
            sendError(exchange, 500, "Failed to start decoder: " + e.getMessage());
        }
    }

    private void handleStop(HttpExchange exchange) throws IOException
    {
        if(!mDecoderRunning || mWebChannel == null)
        {
            JsonObject result = new JsonObject();
            result.addProperty("status", "not_running");
            sendJson(exchange, 200, result);
            return;
        }

        try
        {
            ChannelProcessingManager cpm = mPlaylistManager.getChannelProcessingManager();
            cpm.stop(mWebChannel);
            addLog("Decoder stopped");
        }
        catch(ChannelException e)
        {
            mLog.error("Error stopping channel", e);
        }

        mDecoderRunning = false;
        mWebChannel = null;

        JsonObject result = new JsonObject();
        result.addProperty("status", "stopped");
        sendJson(exchange, 200, result);

        mLog.info("Web UI: Decoder stopped");
    }

    private void handleStatus(HttpExchange exchange) throws IOException
    {
        JsonObject result = new JsonObject();
        result.addProperty("running", mDecoderRunning);

        // Return new log lines since last poll
        JsonArray logArray = new JsonArray();
        synchronized(mLogLines)
        {
            while(mLogReadPos < mLogLines.size())
            {
                logArray.add(mLogLines.get(mLogReadPos));
                mLogReadPos++;
            }
        }
        result.add("log", logArray);

        // Active channel info
        if(mDecoderRunning && mWebChannel != null)
        {
            result.addProperty("channel_name", mWebChannel.getName());
        }

        sendJson(exchange, 200, result);
    }

    /**
     * Add a line to the activity log (called by status handler and event listeners).
     */
    public void addLog(String message)
    {
        synchronized(mLogLines)
        {
            if(mLogLines.size() >= MAX_LOG_LINES)
            {
                mLogLines.remove(0);
                if(mLogReadPos > 0) mLogReadPos--;
            }
            mLogLines.add(message);
        }
    }

    /**
     * Whether the web-initiated decoder is currently running.
     */
    public boolean isDecoderRunning()
    {
        return mDecoderRunning;
    }

    /**
     * Get the web-started channel, if any.
     */
    public Channel getWebChannel()
    {
        return mWebChannel;
    }
}
