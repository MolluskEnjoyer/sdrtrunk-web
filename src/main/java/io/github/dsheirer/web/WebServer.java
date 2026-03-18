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
package io.github.dsheirer.web;

import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.web.handler.DecoderApiHandler;
import io.github.dsheirer.web.handler.RadioReferenceApiHandler;
import io.github.dsheirer.web.handler.StaticFileHandler;
import io.github.dsheirer.web.handler.StatusApiHandler;
import io.github.dsheirer.web.handler.TunerApiHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server that provides a web UI for SDRTrunk.
 * Replaces the Swing/JavaFX GUI with a browser-based interface.
 */
public class WebServer
{
    private static final Logger mLog = LoggerFactory.getLogger(WebServer.class);
    private static final int DEFAULT_PORT = 8080;

    private HttpServer mServer;
    private final int mPort;
    private final PlaylistManager mPlaylistManager;
    private final TunerManager mTunerManager;
    private final UserPreferences mUserPreferences;

    public WebServer(PlaylistManager playlistManager, TunerManager tunerManager,
                     UserPreferences userPreferences, int port)
    {
        mPlaylistManager = playlistManager;
        mTunerManager = tunerManager;
        mUserPreferences = userPreferences;
        mPort = port;
    }

    public WebServer(PlaylistManager playlistManager, TunerManager tunerManager,
                     UserPreferences userPreferences)
    {
        this(playlistManager, tunerManager, userPreferences, DEFAULT_PORT);
    }

    /**
     * Start the web server and register all API endpoints.
     */
    public void start() throws IOException
    {
        mServer = HttpServer.create(new InetSocketAddress(mPort), 0);
        mServer.setExecutor(Executors.newFixedThreadPool(4));

        ChannelProcessingManager channelProcessingManager = mPlaylistManager.getChannelProcessingManager();

        // API handlers
        RadioReferenceApiHandler rrHandler = new RadioReferenceApiHandler(mPlaylistManager, mUserPreferences);
        DecoderApiHandler decoderHandler = new DecoderApiHandler(mPlaylistManager, mTunerManager, mUserPreferences);
        StatusApiHandler statusHandler = new StatusApiHandler(mPlaylistManager, mTunerManager, decoderHandler);
        TunerApiHandler tunerHandler = new TunerApiHandler(mTunerManager);

        mServer.createContext("/api/rr/", rrHandler);
        mServer.createContext("/api/decoder/", decoderHandler);
        mServer.createContext("/api/status", statusHandler);
        mServer.createContext("/api/tuners", tunerHandler);

        // Static file handler serves the web UI
        mServer.createContext("/", new StaticFileHandler());

        mServer.start();
        mLog.info("Web server started on port {} - open http://localhost:{}", mPort, mPort);
    }

    /**
     * Stop the web server.
     */
    public void stop()
    {
        if(mServer != null)
        {
            mServer.stop(2);
            mLog.info("Web server stopped");
        }
    }

    public int getPort()
    {
        return mPort;
    }
}
