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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
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
 * Casambi driver - interface to Casambi REST API
 *
 * Based on casambi-master by Olof Hellquist https://github.com/awahlig/casambi-master
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

    // Connection status

    private CasambiDriverLogger messageLogger;
    private String casambiNetworkId;
    private String casambiSessionId;
    private int casambiWireId;

    final Logger logger = LoggerFactory.getLogger(CasambiDriverRest.class);

    // Constructor, needs developer key, email (user id), user and network passwords
    public CasambiDriverRest(String key, String user, String usrPw, String netPw, CasambiDriverLogger msgLogger) {
        try {
            casaServer = new URL("https://door.casambi.com/");
        } catch (Exception e) {
            logger.error("CasambiDriverJson: exception", e);
            casaServer = null;
        }
        casambiWireId = 1;
        casambiSessionId = "";
        casambiNetworkId = "";

        messageLogger = msgLogger;
        apiKey = key;
        userId = user;
        userPassword = usrPw;
        networkPassword = netPw;
    };

    public CasambiDriverSocket getSocket() {
        return new CasambiDriverSocket(apiKey, casambiSessionId, casambiNetworkId, casambiWireId, messageLogger);
    }

    // --- REST section -----------------------------------------------------------------------------------------------

    private void checkHttpResponse(String functionName, URL url, HttpResponse<String> response)
            throws CasambiException {
        if (response.statusCode() != 200) {
            String msg = String.format("%s -url: %s, got invalid status code: %d, %s", functionName, url.toString(),
                    response.statusCode(), response.body());
            logger.error(msg);
            throw new CasambiException(msg);
        } else {
            logger.trace("{} - success", functionName);
            messageLogger.dumpJsonWithMessage("+++ " + functionName + " +++", response.body());
        }
    }

    // Creates user session and returns session info
    public @Nullable CasambiMessageSession createUserSession()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL url = new URL(casaServer, "/v1/users/session");
        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("email", userId);
        reqJson.addProperty("password", userPassword);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(url.toString()))
                .headers("X-Casambi-Key", apiKey, "Content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqJson.toString())).build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("createUserSesssion", url, response);

        Gson gson = new Gson();
        CasambiMessageSession sessObj = gson.fromJson(response.body(), CasambiMessageSession.class);
        if (sessObj != null) {
            casambiSessionId = sessObj.sessionId;
        }
        return sessObj;
    };

    // Creates network session and returns network info
    public @Nullable Map<String, CasambiMessageNetwork> createNetworkSession()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL url = new URL(casaServer, "/v1/networks/session");
        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("email", userId);
        reqJson.addProperty("password", networkPassword);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(url.toString()))
                .headers("X-Casambi-Key", apiKey, "Content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqJson.toString())).build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("createUserSesssion", url, response);

        Gson gson = new Gson();
        Type networkMapType = new TypeToken<Map<String, CasambiMessageNetwork>>() {
        }.getType();
        Map<String, CasambiMessageNetwork> networks = gson.fromJson(response.body(), networkMapType);
        if (networks != null) {
            for (CasambiMessageNetwork network : networks.values()) {
                casambiNetworkId = network.id;
            }
        }
        return networks;
    };

    private HttpRequest makeHttpGet(URL url) throws URISyntaxException {
        return HttpRequest.newBuilder().uri(new URI(url.toString())).headers("X-Casambi-Key", apiKey,
                "X-Casambi-Session", casambiSessionId, "Content-type", "application/json").GET().build();
    }

    // Queries network and returns network information
    public JsonObject getNetworkInformation()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId);

        HttpResponse<String> response = HttpClient.newBuilder().build().send(makeHttpGet(url),
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("getNetworkInformation", url, response);

        JsonObject networkInfo = JsonParser.parseString(response.body()).getAsJsonObject();
        return networkInfo;
    };

    public @Nullable CasambiMessageNetworkState getNetworkState()
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/state");

        HttpResponse<String> response = HttpClient.newBuilder().build().send(makeHttpGet(url),
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("getNetworkState", url, response);

        Gson gson = new Gson();
        CasambiMessageNetworkState networkState = gson.fromJson(response.body(), CasambiMessageNetworkState.class);
        return networkState;
    };

    // FIXME: convert to message object
    public JsonObject getNetworkDatapoints(LocalDateTime from, LocalDateTime to, int sensorType)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddhhmmss");
        String fromTime = from.format(fmt);
        String toTime = to.format(fmt);
        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/datapoints?sensorType=" + sensorType
                + "&from=" + fromTime + "&to=" + toTime);

        HttpResponse<String> response = HttpClient.newBuilder().build().send(makeHttpGet(url),
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("getNetworkState", url, response);

        // Wrap response into object because gson does not seem to like naked json-arrays
        String res = "{\"datapoints\":" + response.body() + "}";
        JsonObject networkDataPoints = JsonParser.parseString(res).getAsJsonObject();
        return networkDataPoints;
    };

    public @Nullable Map<String, CasambiMessageUnit> getUnitList()
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/units");

        HttpResponse<String> response = HttpClient.newBuilder().build().send(makeHttpGet(url),
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("getUnitList", url, response);

        Gson gson = new Gson();
        Type unitMapType = new TypeToken<Map<String, CasambiMessageUnit>>() {
        }.getType();
        Map<String, CasambiMessageUnit> unitList = gson.fromJson(response.body(), unitMapType);
        return unitList;
    };

    public @Nullable CasambiMessageUnit getUnitState(int unitId)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/units/" + unitId + "/state");

        HttpResponse<String> response = HttpClient.newBuilder().build().send(makeHttpGet(url),
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("getUnitState", url, response);

        Gson gson = new Gson();
        CasambiMessageUnit unitState = gson.fromJson(response.body(), CasambiMessageUnit.class);
        return unitState;
    };

    public @Nullable Map<String, CasambiMessageScene> getScenes()
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/scenes");

        HttpResponse<String> response = HttpClient.newBuilder().build().send(makeHttpGet(url),
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("getScenes", url, response);

        Gson gson = new Gson();
        Type sceneMapType = new TypeToken<Map<String, CasambiMessageScene>>() {
        }.getType();
        Map<String, CasambiMessageScene> scenes = gson.fromJson(response.body(), sceneMapType);
        return scenes;
    };

    // FIXME: convert to message object
    public JsonObject getFixtureInfo(int unitId)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL url = new URL(casaServer, "/v1/fixtures/" + unitId);

        HttpResponse<String> response = HttpClient.newBuilder().build().send(makeHttpGet(url),
                HttpResponse.BodyHandlers.ofString());

        checkHttpResponse("getFixtureInfo", url, response);

        JsonObject fixtureInfo = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getUnitState: " + ppJson(unitInfo));
        return fixtureInfo;
    };
}
