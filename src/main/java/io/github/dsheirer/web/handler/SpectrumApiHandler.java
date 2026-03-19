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
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTResultsListener;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * REST API handler for spectrum/FFT data.
 * Provides real-time spectral data from the tuner for web-based spectrum and waterfall display.
 *
 * Endpoints:
 *   GET /api/spectrum/data   - Get current FFT frame (dB values)
 *   POST /api/spectrum/start - Start spectrum processing
 *   POST /api/spectrum/stop  - Stop spectrum processing
 */
public class SpectrumApiHandler extends BaseApiHandler implements DFTResultsListener
{
    private static final Logger mLog = LoggerFactory.getLogger(SpectrumApiHandler.class);
    private static final String CONTEXT_PREFIX = "/api/spectrum/";
    private static final int WEB_FRAME_RATE = 15;
    private static final DFTSize WEB_DFT_SIZE = DFTSize.FFT04096;

    private final TunerManager mTunerManager;
    private ComplexDftProcessor mDftProcessor;
    private ComplexDecibelConverter mConverter;
    private volatile float[] mLatestFrame;
    private volatile boolean mRunning;
    private Tuner mActiveTuner;

    public SpectrumApiHandler(TunerManager tunerManager)
    {
        mTunerManager = tunerManager;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException
    {
        String subPath = getSubPath(exchange, CONTEXT_PREFIX);
        String method = exchange.getRequestMethod().toUpperCase();

        switch(subPath)
        {
            case "data":
                if("GET".equals(method)) handleData(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "start":
                if("POST".equals(method)) handleStart(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "stop":
                if("POST".equals(method)) handleStop(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            default:
                sendError(exchange, 404, "Not found: " + subPath);
        }
    }

    /**
     * Returns the latest FFT frame as JSON with dB values and metadata.
     */
    private void handleData(HttpExchange exchange) throws IOException
    {
        JsonObject result = new JsonObject();
        result.addProperty("running", mRunning);

        float[] frame = mLatestFrame;
        if(frame != null && mRunning)
        {
            JsonArray bins = new JsonArray(frame.length);
            for(float db : frame)
            {
                // Round to 1 decimal to reduce payload size
                bins.add(Math.round(db * 10.0f) / 10.0f);
            }
            result.add("bins", bins);
            result.addProperty("fft_size", WEB_DFT_SIZE.getSize());

            if(mActiveTuner != null)
            {
                result.addProperty("center_freq", mActiveTuner.getTunerController().getFrequency());
                result.addProperty("sample_rate", mActiveTuner.getTunerController().getSampleRate());
            }
        }

        sendJson(exchange, 200, result);
    }

    /**
     * Start spectrum processing - hooks into the first available tuner.
     */
    private void handleStart(HttpExchange exchange) throws IOException
    {
        if(mRunning)
        {
            JsonObject result = new JsonObject();
            result.addProperty("status", "already_running");
            sendJson(exchange, 200, result);
            return;
        }

        // Find the first available tuner
        Tuner tuner = null;
        for(DiscoveredTuner dt : mTunerManager.getAvailableTuners())
        {
            if(dt.getTuner() != null)
            {
                tuner = dt.getTuner();
                break;
            }
        }

        if(tuner == null)
        {
            sendError(exchange, 404, "No tuner available");
            return;
        }

        mActiveTuner = tuner;

        // Set up the FFT processing chain
        mDftProcessor = new ComplexDftProcessor();
        mDftProcessor.setDFTSize(WEB_DFT_SIZE);
        mDftProcessor.setFrameRate(WEB_FRAME_RATE);

        mConverter = new ComplexDecibelConverter();
        mConverter.addListener(this);
        mDftProcessor.addConverter(mConverter);

        // Hook into tuner's sample stream
        tuner.getTunerController().addBufferListener(mDftProcessor);

        mRunning = true;
        mLog.info("Spectrum processing started for tuner: {}", tuner.getPreferredName());

        JsonObject result = new JsonObject();
        result.addProperty("status", "started");
        result.addProperty("tuner", tuner.getPreferredName());
        result.addProperty("fft_size", WEB_DFT_SIZE.getSize());
        result.addProperty("frame_rate", WEB_FRAME_RATE);
        sendJson(exchange, 200, result);
    }

    /**
     * Stop spectrum processing.
     */
    private void handleStop(HttpExchange exchange) throws IOException
    {
        stopProcessing();

        JsonObject result = new JsonObject();
        result.addProperty("status", "stopped");
        sendJson(exchange, 200, result);
    }

    private void stopProcessing()
    {
        if(mActiveTuner != null && mDftProcessor != null)
        {
            mActiveTuner.getTunerController().removeBufferListener(mDftProcessor);
        }

        if(mDftProcessor != null)
        {
            mDftProcessor.dispose();
            mDftProcessor = null;
        }

        if(mConverter != null)
        {
            mConverter.dispose();
            mConverter = null;
        }

        mActiveTuner = null;
        mLatestFrame = null;
        mRunning = false;
        mLog.info("Spectrum processing stopped");
    }

    /**
     * Called by the ComplexDecibelConverter with each FFT frame (dB values).
     */
    @Override
    public void receive(float[] results)
    {
        mLatestFrame = results;
    }
}
