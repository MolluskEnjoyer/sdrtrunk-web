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
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.rrapi.RadioReferenceException;
import io.github.dsheirer.rrapi.RadioReferenceService;
import io.github.dsheirer.rrapi.type.AuthorizationInformation;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.Site;
import io.github.dsheirer.rrapi.type.SiteFrequency;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.Tag;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.UserInfo;
import io.github.dsheirer.rrapi.type.ZipInfo;
import io.github.dsheirer.service.radioreference.RadioReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API handler for RadioReference integration.
 * Bridges the web frontend to SDRTrunk's existing RadioReference SOAP service.
 *
 * Endpoints:
 *   POST /api/rr/login     - Login with username/password
 *   POST /api/rr/logout    - Logout
 *   GET  /api/rr/zip       - Lookup county by zip code (?code=XXXXX)
 *   GET  /api/rr/systems   - List P25 systems in county (?county_id=N)
 *   GET  /api/rr/talkgroups - Get talkgroups for system (?system_id=N)
 *   GET  /api/rr/sites     - Get sites and frequencies (?system_id=N)
 */
public class RadioReferenceApiHandler extends BaseApiHandler
{
    private static final Logger mLog = LoggerFactory.getLogger(RadioReferenceApiHandler.class);
    private static final String CONTEXT_PREFIX = "/api/rr/";

    private final PlaylistManager mPlaylistManager;
    private final UserPreferences mUserPreferences;
    private RadioReferenceService mService;
    private String mLoggedInUser;
    private String mExpires;
    private Map<Integer, Tag> mTagMap = new HashMap<>();

    public RadioReferenceApiHandler(PlaylistManager playlistManager, UserPreferences userPreferences)
    {
        mPlaylistManager = playlistManager;
        mUserPreferences = userPreferences;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException
    {
        String subPath = getSubPath(exchange, CONTEXT_PREFIX);
        String method = exchange.getRequestMethod().toUpperCase();

        switch(subPath)
        {
            case "login":
                if("POST".equals(method)) handleLogin(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "logout":
                if("POST".equals(method)) handleLogout(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "zip":
                if("GET".equals(method)) handleZipLookup(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "systems":
                if("GET".equals(method)) handleSystems(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "talkgroups":
                if("GET".equals(method)) handleTalkgroups(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            case "sites":
                if("GET".equals(method)) handleSites(exchange);
                else sendError(exchange, 405, "Method not allowed");
                break;
            default:
                sendError(exchange, 404, "Not found: " + subPath);
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException
    {
        JsonObject body = readJsonBody(exchange);
        String username = body.has("username") ? body.get("username").getAsString() : null;
        String password = body.has("password") ? body.get("password").getAsString() : null;

        if(username == null || username.isEmpty() || password == null || password.isEmpty())
        {
            sendError(exchange, 400, "Username and password required");
            return;
        }

        try
        {
            RadioReference.LoginStatus status = RadioReference.testConnectionWithExp(username, password);

            if(status == RadioReference.LoginStatus.INVALID_LOGIN)
            {
                sendError(exchange, 401, "Invalid username or password");
                return;
            }

            if(status == RadioReference.LoginStatus.EXPIRED_PREMIUM)
            {
                sendError(exchange, 403, "RadioReference premium subscription has expired");
                return;
            }

            // Create authenticated service
            AuthorizationInformation auth = RadioReference.getAuthorizatonInformation(username, password);
            mService = new RadioReferenceService(auth);

            UserInfo userInfo = mService.getUserInfo();
            mLoggedInUser = username;
            mExpires = userInfo != null ? userInfo.getExpirationDate() : "Unknown";

            // Also set credentials on SDRTrunk's RadioReference instance
            RadioReference rr = mPlaylistManager.getRadioReference();
            rr.setAuthorizationInformation(auth);

            // Load tag map for talkgroup category names
            try
            {
                mTagMap = mService.getTagsMap();
            }
            catch(RadioReferenceException e)
            {
                mLog.warn("Could not load tag map", e);
            }

            JsonObject result = new JsonObject();
            result.addProperty("username", mLoggedInUser);
            result.addProperty("expires", mExpires);
            result.addProperty("status", "ok");
            sendJson(exchange, 200, result);

            mLog.info("Web UI: RadioReference login successful for user [{}]", username);
        }
        catch(RadioReferenceException e)
        {
            mLog.error("RadioReference login failed", e);
            sendError(exchange, 401, "Login failed: " + e.getMessage());
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException
    {
        mService = null;
        mLoggedInUser = null;
        mExpires = null;

        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        sendJson(exchange, 200, result);
    }

    private void requireLogin(HttpExchange exchange) throws IOException
    {
        if(mService == null)
        {
            sendError(exchange, 401, "Not logged in to RadioReference");
        }
    }

    private void handleZipLookup(HttpExchange exchange) throws IOException
    {
        if(mService == null)
        {
            sendError(exchange, 401, "Not logged in to RadioReference");
            return;
        }

        Map<String, String> params = parseQuery(exchange);
        String code = params.get("code");

        if(code == null || code.isEmpty())
        {
            sendError(exchange, 400, "Zip code required (?code=XXXXX)");
            return;
        }

        try
        {
            int zipcode = Integer.parseInt(code);
            ZipInfo zipInfo = mService.getZipcodeInfo(zipcode);

            if(zipInfo == null)
            {
                sendError(exchange, 404, "No information found for zip code " + code);
                return;
            }

            JsonObject result = new JsonObject();
            result.addProperty("county_id", zipInfo.getCountyId());
            result.addProperty("county_name", zipInfo.getCity());
            result.addProperty("state_name", "State ID: " + zipInfo.getStateId());
            result.addProperty("state_id", zipInfo.getStateId());
            sendJson(exchange, 200, result);
        }
        catch(NumberFormatException e)
        {
            sendError(exchange, 400, "Invalid zip code format");
        }
        catch(RadioReferenceException e)
        {
            mLog.error("Zip lookup failed for code [{}]", code, e);
            sendError(exchange, 500, "Zip lookup failed: " + e.getMessage());
        }
    }

    private void handleSystems(HttpExchange exchange) throws IOException
    {
        if(mService == null)
        {
            sendError(exchange, 401, "Not logged in to RadioReference");
            return;
        }

        Map<String, String> params = parseQuery(exchange);
        String countyId = params.get("county_id");

        if(countyId == null || countyId.isEmpty())
        {
            sendError(exchange, 400, "county_id required");
            return;
        }

        try
        {
            int id = Integer.parseInt(countyId);
            CountyInfo countyInfo = mService.getCountyInfo(id);

            JsonArray systemsArray = new JsonArray();

            if(countyInfo != null && countyInfo.getSystems() != null)
            {
                for(System sys : countyInfo.getSystems())
                {
                    JsonObject sysObj = new JsonObject();
                    sysObj.addProperty("system_id", sys.getSystemId());
                    sysObj.addProperty("name", sys.getName());
                    sysObj.addProperty("type_name", sys.getTypeName());
                    systemsArray.add(sysObj);
                }
            }

            JsonObject result = new JsonObject();
            result.add("systems", systemsArray);
            sendJson(exchange, 200, result);
        }
        catch(NumberFormatException e)
        {
            sendError(exchange, 400, "Invalid county_id format");
        }
        catch(RadioReferenceException e)
        {
            mLog.error("Systems lookup failed for county [{}]", countyId, e);
            sendError(exchange, 500, "Systems lookup failed: " + e.getMessage());
        }
    }

    private void handleTalkgroups(HttpExchange exchange) throws IOException
    {
        if(mService == null)
        {
            sendError(exchange, 401, "Not logged in to RadioReference");
            return;
        }

        Map<String, String> params = parseQuery(exchange);
        String systemId = params.get("system_id");

        if(systemId == null || systemId.isEmpty())
        {
            sendError(exchange, 400, "system_id required");
            return;
        }

        try
        {
            int id = Integer.parseInt(systemId);
            List<Talkgroup> talkgroups = mService.getTalkgroups(id);

            JsonArray tgArray = new JsonArray();

            if(talkgroups != null)
            {
                for(Talkgroup tg : talkgroups)
                {
                    JsonObject tgObj = new JsonObject();
                    tgObj.addProperty("tg_id", tg.getTalkgroupId());
                    tgObj.addProperty("tg_dec", tg.getDecimalValue());
                    tgObj.addProperty("alpha_tag", tg.getAlphaTag() != null ? tg.getAlphaTag() : "");
                    tgObj.addProperty("description", tg.getDescription() != null ? tg.getDescription() : "");
                    tgObj.addProperty("mode", mapMode(tg.getMode()));
                    tgObj.addProperty("encryption", tg.getEncryptionState());
                    tgObj.addProperty("tag", getTagName(tg));
                    tgArray.add(tgObj);
                }
            }

            JsonObject result = new JsonObject();
            result.add("talkgroups", tgArray);
            sendJson(exchange, 200, result);

            mLog.info("Web UI: Loaded {} talkgroups for system {}", tgArray.size(), systemId);
        }
        catch(NumberFormatException e)
        {
            sendError(exchange, 400, "Invalid system_id format");
        }
        catch(RadioReferenceException e)
        {
            mLog.error("Talkgroups lookup failed for system [{}]", systemId, e);
            sendError(exchange, 500, "Talkgroups lookup failed: " + e.getMessage());
        }
    }

    private void handleSites(HttpExchange exchange) throws IOException
    {
        if(mService == null)
        {
            sendError(exchange, 401, "Not logged in to RadioReference");
            return;
        }

        Map<String, String> params = parseQuery(exchange);
        String systemId = params.get("system_id");

        if(systemId == null || systemId.isEmpty())
        {
            sendError(exchange, 400, "system_id required");
            return;
        }

        try
        {
            int id = Integer.parseInt(systemId);
            List<Site> sites = mService.getSites(id);

            JsonArray sitesArray = new JsonArray();

            if(sites != null)
            {
                for(Site site : sites)
                {
                    JsonObject siteObj = new JsonObject();
                    siteObj.addProperty("site_id", site.getSiteId());
                    siteObj.addProperty("site_number", site.getSiteNumber());
                    siteObj.addProperty("description", site.getDescription() != null ? site.getDescription() : "");
                    siteObj.addProperty("rfss", site.getRfss());

                    JsonArray freqsArray = new JsonArray();
                    if(site.getSiteFrequencies() != null)
                    {
                        for(SiteFrequency freq : site.getSiteFrequencies())
                        {
                            JsonObject freqObj = new JsonObject();
                            freqObj.addProperty("frequency", freq.getFrequency());
                            freqObj.addProperty("is_control", freq.getUse() != null && freq.getUse().equals("d"));
                            freqsArray.add(freqObj);
                        }
                    }
                    siteObj.add("freqs", freqsArray);
                    sitesArray.add(siteObj);
                }
            }

            JsonObject result = new JsonObject();
            result.add("sites", sitesArray);
            sendJson(exchange, 200, result);
        }
        catch(NumberFormatException e)
        {
            sendError(exchange, 400, "Invalid system_id format");
        }
        catch(RadioReferenceException e)
        {
            mLog.error("Sites lookup failed for system [{}]", systemId, e);
            sendError(exchange, 500, "Sites lookup failed: " + e.getMessage());
        }
    }

    /**
     * Extract the first tag name from a Talkgroup's tags array using the cached tag map.
     */
    private String getTagName(Talkgroup tg)
    {
        Tag[] tags = tg.getTags();
        if(tags != null && tags.length > 0)
        {
            Tag lookup = mTagMap.get(tags[0].getTagId());
            if(lookup != null && lookup.getDescription() != null)
            {
                return lookup.getDescription();
            }
        }
        return "Other";
    }

    /**
     * Map RadioReference mode values to display codes.
     */
    private String mapMode(String mode)
    {
        if(mode == null) return "?";
        return switch(mode.toLowerCase())
        {
            case "d", "de" -> "D";   // Digital
            case "a" -> "A";          // Analog
            case "m" -> "M";          // Mixed
            case "t", "tdma" -> "T"; // TDMA
            case "e" -> "E";          // Encrypted
            default -> mode.substring(0, 1).toUpperCase();
        };
    }
}
