/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.casambisimple.internal.driver;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageNetwork;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageSession;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

/**
 * The {@link CasambiDriverRest} manages the REST connection to the Casambi system
 *
 * It creates the user and network session, and sends information requests to the
 * Casambi system.
 *
 * Based on casambi-master by Olof Hellquist https://github.com/awahlig/casambi-master and
 * the Casambi documentation at https://developer.casambi.com/
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiDriverRest {

    // Connection parameters

    private @Nullable URL casaServer;
    private String userId;
    private String userPassword;
    private String networkPassword;
    private String apiKey;
    // FIXME: possibly needs to be converted to asynchronous http calls
    private final HttpClient httpClient;
    private final WebSocketClient webSocketClient;

    // Connection status

    private CasambiDriverLogger messageLogger;
    private String casambiNetworkId;
    private String casambiSessionId;
    private int casambiWireId;

    final Logger logger = LoggerFactory.getLogger(CasambiDriverRest.class);

    // Constructor, needs developer key, email (user id), user and network passwords
    public CasambiDriverRest(String key, String user, String usrPw, String netPw, CasambiDriverLogger msgLogger,
            WebSocketClient webSocketClient, HttpClient httpClient) {
        try {
            casaServer = new URL("https://door.casambi.com/");
        } catch (Exception e) {
            logger.error("CasambiDriverJson: new URL - exception", e);
            casaServer = null;
        }
        casambiWireId = 1;
        casambiSessionId = "";
        casambiNetworkId = "";
        this.httpClient = httpClient;
        this.webSocketClient = webSocketClient;
        try {
            logger.trace("CasambiDriverRest: before httpClient.start");
            httpClient.start();
        } catch (Exception e) {
            // FIXME: stop this on shutdown
            logger.error("CasambiDriverRest: httpClient.start - exception {}", e.getMessage());
            casaServer = null;
        }
        messageLogger = msgLogger;
        apiKey = key;
        userId = user;
        userPassword = usrPw;
        networkPassword = netPw;
    }

    public CasambiDriverSocket getSocket() {
        return new CasambiDriverSocket(apiKey, casambiSessionId, casambiNetworkId, casambiWireId, messageLogger,
                webSocketClient);
    }

    public void close() {
        try {
            httpClient.stop();
        } catch (Exception e) {
            logger.error("CasambiDriverRest.close: httpClient.stop - exception {}", e.getMessage());
        }
        casaServer = null;
    }

    // --- REST section -----------------------------------------------------------------------------------------------

    private void checkHttpResponse(String functionName, URL url, @Nullable ContentResponse response)
            throws CasambiException {
        if (response == null) {
            logger.error("{} - error - null response", functionName);
            throw new CasambiException("checkHttpResponse - got null response");
        } else if (response.getStatus() != 200) {
            String msg = String.format("%s -url: %s, got invalid status code: %d, %s", functionName, url.toString(),
                    response.getStatus(), response.getContentAsString());
            logger.error(msg);
            throw new CasambiException(msg);
        } else {
            // logger.trace("{} - success", functionName);
            // logger.trace("{} - response - {}", functionName, response.getContentAsString());
            messageLogger.dumpJsonWithMessage("+++ " + functionName + " +++", response.getContentAsString());
        }
    }

    // Creates user session and returns session info
    public @Nullable CasambiMessageSession createUserSession() throws URISyntaxException, CasambiException,
            TimeoutException, ExecutionException, MalformedURLException, InterruptedException {

        URL url = new URL(casaServer, "/v1/users/session");
        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("email", userId);
        reqJson.addProperty("password", userPassword);

        // logger.trace("createUserSession: before sending request");
        ContentResponse response = null;
        try {
            response = httpClient.POST(url.toString()).header("Content-Type", "application/json")
                    .header("X-Casambi-Key", apiKey)
                    .content(new StringContentProvider(reqJson.toString()), "application/json").send();
            checkHttpResponse("createUserSesssion", url, response);
        } catch (Exception e) {
            logger.trace("createUserSession: Exception {}", e.getMessage());
            // logger.trace(e.getStackTrace().toString());
        }
        Gson gson = new Gson();
        CasambiMessageSession sessObj = gson.fromJson(response.getContentAsString(), CasambiMessageSession.class);
        if (sessObj != null) {
            casambiSessionId = sessObj.sessionId;
        }
        return sessObj;
    }

    // Creates network session and returns network info
    public @Nullable Map<String, CasambiMessageNetwork> createNetworkSession() throws URISyntaxException, IOException,
            InterruptedException, CasambiException, TimeoutException, ExecutionException {

        URL url = new URL(casaServer, "/v1/networks/session");
        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("email", userId);
        reqJson.addProperty("password", networkPassword);

        // logger.trace("createNetworkSession: before sending request");
        ContentResponse response = null;
        try {
            response = httpClient.POST(url.toString()).header("Content-Type", "application/json")
                    .header("X-Casambi-Key", apiKey)
                    .content(new StringContentProvider(reqJson.toString()), "application/json").send();
            checkHttpResponse("createNetworkSesssion", url, response);
        } catch (Exception e) {
            logger.trace("createNetworkSession: Exception {}", e.getMessage());
        }

        Gson gson = new Gson();
        Type networkMapType = new TypeToken<Map<String, CasambiMessageNetwork>>() {
        }.getType();
        Map<String, CasambiMessageNetwork> networks = gson.fromJson(response.getContentAsString(), networkMapType);
        if (networks != null) {
            for (CasambiMessageNetwork network : networks.values()) {
                casambiNetworkId = network.id;
            }
        }
        return networks;
    }

    private Request makeHttpGet(URL url) {
        return httpClient.newRequest(url.toString()).method(HttpMethod.GET).header("Content-Type", "application/json")
                .header("X-Casambi-Key", apiKey).header("X-Casambi-Session", casambiSessionId);
    }

    // Queries network and returns network information
    public @Nullable JsonObject getNetworkInformation()
            throws MalformedURLException, InterruptedException, TimeoutException, ExecutionException, CasambiException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId);
        ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getNetworkInformation", url, response);
        JsonObject networkInfo = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
        return networkInfo;
    }

    public @Nullable CasambiMessageNetworkState getNetworkState() throws IOException, InterruptedException,
            URISyntaxException, CasambiException, TimeoutException, ExecutionException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/state");
        ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getNetworkState", url, response);

        Gson gson = new Gson();
        CasambiMessageNetworkState networkState = gson.fromJson(response.getContentAsString(),
                CasambiMessageNetworkState.class);
        return networkState;
    }

    // FIXME: convert to message object
    public JsonObject getNetworkDatapoints(LocalDateTime from, LocalDateTime to, int sensorType) throws IOException,
            InterruptedException, URISyntaxException, CasambiException, TimeoutException, ExecutionException {

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddhhmmss");
        String fromTime = from.format(fmt);
        String toTime = to.format(fmt);
        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/datapoints?sensorType=" + sensorType
                + "&from=" + fromTime + "&to=" + toTime);
        ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getNetworkState", url, response);

        // Wrap response into object because gson does not seem to like naked json-arrays
        String res = "{\"datapoints\":" + response.getContentAsString() + "}";
        JsonObject networkDataPoints = JsonParser.parseString(res).getAsJsonObject();
        return networkDataPoints;
    }

    public @Nullable Map<String, CasambiMessageUnit> getUnitList() throws IOException, InterruptedException,
            URISyntaxException, CasambiException, TimeoutException, ExecutionException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/units");
        ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getUnitList", url, response);

        Gson gson = new Gson();
        Type unitMapType = new TypeToken<Map<String, CasambiMessageUnit>>() {
        }.getType();
        Map<String, CasambiMessageUnit> unitList = gson.fromJson(response.getContentAsString(), unitMapType);
        return unitList;
    }

    public @Nullable CasambiMessageUnit getUnitState(int unitId) throws IOException, InterruptedException,
            URISyntaxException, CasambiException, TimeoutException, ExecutionException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/units/" + unitId + "/state");
        ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getUnitState", url, response);

        Gson gson = new Gson();
        CasambiMessageUnit unitState = gson.fromJson(response.getContentAsString(), CasambiMessageUnit.class);
        return unitState;
    }

    public @Nullable Map<String, CasambiMessageScene> getScenes() throws IOException, InterruptedException,
            URISyntaxException, CasambiException, TimeoutException, ExecutionException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/scenes");
        ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getScenes", url, response);

        Gson gson = new Gson();
        Type sceneMapType = new TypeToken<Map<String, CasambiMessageScene>>() {
        }.getType();
        Map<String, CasambiMessageScene> scenes = gson.fromJson(response.getContentAsString(), sceneMapType);
        return scenes;
    }

    // FIXME: convert to message object
    public JsonObject getFixtureInfo(int unitId) throws IOException, InterruptedException, URISyntaxException,
            CasambiException, TimeoutException, ExecutionException {

        URL url = new URL(casaServer, "/v1/fixtures/" + unitId);
        ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getFixtureInfo", url, response);

        JsonObject fixtureInfo = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
        return fixtureInfo;
    }
}
