/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageNetwork;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageNetworkState;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageScene;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageSession;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageUnit;
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
 * Based on casambi-master (Python) by Olof Hellquist https://github.com/awahlig/casambi-master and
 * the Casambi documentation at https://developer.casambi.com/
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDriverRest {

    // Connection parameters

    private @Nullable URL casaServer;
    private final String userId;
    private final String userPassword;
    private final String networkPassword;
    private final String apiKey;
    private final HttpClient httpClient;
    private final WebSocketClient webSocketClient;
    private final CasambiSimpleDriverLogger messageLogger;

    // Connection status

    private String casambiNetworkId;
    private String casambiSessionId;
    private final int casambiWireId; // only supporting one network here

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleDriverRest.class);

    /**
     * CasambiSimpleDriverRest constructor sets up the REST interface and calls setup of the Socket interface. The
     * methods handle the REST calls and replies.
     *
     * @param key - Casambi (developer) access key, request from Casambi for user id
     * @param user - Casambi (developer) user id, setup with Casambi app
     * @param usrPw - Casambi (developer) user password, setup with Casambi app
     * @param netPw - Casambi (developer) network password, setup with Casambi app
     * @param msgLogger - class to (optionally) log messages received from the Casambi cloud
     * @param webSocketClient - from the OpenHAB webSocketClientFactory, used by the socket driver
     * @param httpClient - from the OpenHAB httpClientFactory, used by the REST driver
     *
     *            FIXME: not all Casambi REST API endpoints are implemented. Missing are: get groups, get unit icon, get
     *            network gallery, get network image, get fixture icon
     */
    public CasambiSimpleDriverRest(String key, String user, String usrPw, String netPw,
            CasambiSimpleDriverLogger msgLogger, WebSocketClient webSocketClient, HttpClient httpClient) {
        logger.debug("CasambiSimpleDriverRest:constructor webSocketClient {}, httpClient {}", webSocketClient,
                httpClient);
        try {
            casaServer = new URL("https://door.casambi.com/");
        } catch (Exception e) {
            logger.warn("CasambiDriverRest:constructor new URL - exception", e);
            casaServer = null;
        }
        casambiWireId = 1;
        casambiSessionId = "";
        casambiNetworkId = "";
        this.httpClient = httpClient;
        this.webSocketClient = webSocketClient;
        // No need to start shared httpClient
        // try {
        // httpClient.start();
        // } catch (Exception e) {
        // logger.error("CasambiDriverRest:constructor httpClient.start exception {}", e.getMessage());
        // casaServer = null;
        // }
        messageLogger = msgLogger;
        apiKey = key;
        userId = user;
        userPassword = usrPw;
        networkPassword = netPw;
    }

    /**
     * getSocket returns a new webSocket. Uses the parameters form the REST constructor.
     *
     * @return the the web-socket
     */
    public CasambiSimpleDriverSocket getNewCasambiSocket() {
        logger.trace("casambiRest:getNewCasabmiSocket");
        return new CasambiSimpleDriverSocket(apiKey, casambiSessionId, casambiNetworkId, casambiWireId, messageLogger,
                webSocketClient);
    }

    /**
     * close the REST interface.
     *
     * FIXME: should this close the web-socket interface as well?
     */
    public void close() {
        // Close socket together with REST client
        // boolean socketOk = webSocketClient.close();
        logger.trace("casambiRest:close - don't stop anything, we are using the shared client");
        // try {
        // httpClient.stop();
        // } catch (Exception e) {
        // logger.error("casambiRest:close httpClient.stop - exception {}", e.getMessage());
        // }
        casaServer = null;
    }

    // --- REST section -----------------------------------------------------------------------------------------------

    /**
     * checkHttpResponse checks the response from a http GET or POST request. Writes
     * the response to the message logger.
     *
     * @param functionName - name of the function calling checkHttpResponse. Used for an error message if needed.
     * @param url - url used in the GET or POST request. Used for an error message if needed.
     * @param response - the response information from the GET or POST request.
     * @throws CasambiSimpleException - if response is not ok (response == null or status != 200)
     */
    private void checkHttpResponse(String functionName, URL url, @Nullable ContentResponse response)
            throws CasambiSimpleException {
        if (response == null) {
            logger.warn("{} - error - null response", functionName);
            throw new CasambiSimpleException("checkHttpResponse - got null response");
        } else if (response.getStatus() != 200) {
            final String msg = String.format("%s -url: %s, got invalid status code: %d, %s", functionName,
                    url.toString(), response.getStatus(), response.getContentAsString());
            logger.warn(msg);
            throw new CasambiSimpleException(msg);
        } else {
            messageLogger.dumpJsonWithMessage("+++ " + functionName + " +++", response.getContentAsString());
        }
    }

    /**
     * createUserSession creates user session and returns session info.
     *
     * @return the session info as returned by the Casambi cloud service
     * @throws URISyntaxException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     * @throws MalformedURLException
     * @throws InterruptedException
     */
    public @Nullable CasambiSimpleMessageSession createUserSession() throws URISyntaxException, CasambiSimpleException,
            TimeoutException, ExecutionException, MalformedURLException, InterruptedException {
        final URL url = new URL(casaServer, "/v1/users/session");
        final JsonObject reqJson = new JsonObject();
        reqJson.addProperty("email", userId);
        reqJson.addProperty("password", userPassword);

        ContentResponse response = null;
        try {
            response = httpClient.POST(url.toString()).header("Content-Type", "application/json")
                    .header("X-Casambi-Key", apiKey)
                    .content(new StringContentProvider(reqJson.toString()), "application/json").send();
            checkHttpResponse("createUserSesssion", url, response);
        } catch (Exception e) {
            logger.warn("createUserSession: Exception {}", e.getMessage());
        }
        if (response != null) {
            final Gson gson = new Gson();
            final CasambiSimpleMessageSession sessObj = gson.fromJson(response.getContentAsString(),
                    CasambiSimpleMessageSession.class);
            if (sessObj != null) {
                casambiSessionId = sessObj.sessionId;
            } else {
                logger.warn("createUserSession: Session object is null. HTTP response was '{}'",
                        response.getContentAsString());
            }

            return sessObj;
        } else {
            return null;
        }
    }

    /**
     * Creates network session and returns network info
     *
     * @return network info as returned by the Casambi cloud service
     * @throws URISyntaxException
     * @throws IOException
     * @throws InterruptedException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    public @Nullable Map<String, CasambiSimpleMessageNetwork> createNetworkSession() throws URISyntaxException,
            IOException, InterruptedException, CasambiSimpleException, TimeoutException, ExecutionException {
        final URL url = new URL(casaServer, "/v1/networks/session");
        final JsonObject reqJson = new JsonObject();
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
            logger.info("createNetworkSession: Exception {}", e.getMessage());
        }

        if (response != null) {
            final Gson gson = new Gson();
            final Type networkMapType = new TypeToken<Map<String, CasambiSimpleMessageNetwork>>() {
            }.getType();
            final Map<String, CasambiSimpleMessageNetwork> networks = gson.fromJson(response.getContentAsString(),
                    networkMapType);
            if (networks != null) {
                for (CasambiSimpleMessageNetwork network : networks.values()) {
                    casambiNetworkId = network.id;
                }
            }
            return networks;
        } else {
            return null;
        }
    }

    /**
     * makeHttpGet sets up a Casambi REST GET request. The request type and the parameters are contained in the URL.
     * Authentication and context information is added.
     *
     * @param url - request url, contains the request type
     * @return a REST request
     */
    private Request makeHttpGet(URL url) {
        return httpClient.newRequest(url.toString()).method(HttpMethod.GET).header("Content-Type", "application/json")
                .header("X-Casambi-Key", apiKey).header("X-Casambi-Session", casambiSessionId);
    }

    /**
     * getNetworkInformation queries the network and returns network information
     *
     * @return network information as returned by the Casambi cloud service. FIXME: this is a generic JSON object.
     * @throws MalformedURLException
     * @throws InterruptedException
     * @throws TimeoutException
     * @throws ExecutionException
     * @throws CasambiSimpleException
     */
    public @Nullable JsonObject getNetworkInformation() throws MalformedURLException, InterruptedException,
            TimeoutException, ExecutionException, CasambiSimpleException {
        final URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId);
        final ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getNetworkInformation", url, response);
        final JsonObject networkInfo = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
        return networkInfo;
    }

    /**
     * getNetworkState queries the luminaires, scenes and groups on the network
     *
     * @return network state data as returned by the Casambi cloud service.
     * @throws IOException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     *
     *             This is called by the pollUnitStatus job in the bridge class
     */
    public @Nullable CasambiSimpleMessageNetworkState getNetworkState() throws IOException, InterruptedException,
            URISyntaxException, CasambiSimpleException, TimeoutException, ExecutionException {
        final URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/state");
        final ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getNetworkState", url, response);

        final Gson gson = new Gson();
        final CasambiSimpleMessageNetworkState networkState = gson.fromJson(response.getContentAsString(),
                CasambiSimpleMessageNetworkState.class);
        return networkState;
    }

    /**
     * getNetworkDatapoints queries data from a sensor type on the Casambi network.
     *
     * @param from - starting time for sensor data
     * @param to - end time for sensor data
     * @param sensorType - integer for sensor type
     * @return sensor data as returned by the Casambi cloud service. FIXME: this is a generic JSON object.
     * @throws IOException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     *
     *             FIXME: uses deprecated api.
     *             FIXME: use new url "https://door.casambi.com/datapoints/networks/NETWORK-ID-HERE/datapoints" + filter
     *             FIXME: see https://developer.casambi.com/#rest-api-request-network-datapoints
     */
    public JsonObject getNetworkDatapoints(LocalDateTime from, LocalDateTime to, int sensorType) throws IOException,
            InterruptedException, URISyntaxException, CasambiSimpleException, TimeoutException, ExecutionException {
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddhhmmss");
        final String fromTime = from.format(fmt);
        final String toTime = to.format(fmt);
        final URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/datapoints?sensorType=" + sensorType
                + "&from=" + fromTime + "&to=" + toTime);
        final ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getNetworkDatapoints", url, response);

        // Wrap response into object because gson does not seem to like naked json-arrays
        final String res = "{\"datapoints\":" + response.getContentAsString() + "}";
        final JsonObject networkDataPoints = JsonParser.parseString(res).getAsJsonObject();
        return networkDataPoints;
    }

    /**
     * getUnitList returns the units on the Casambi network
     *
     * @return unit data
     * @throws IOException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    public @Nullable Map<String, CasambiSimpleMessageUnit> getUnitList() throws IOException, InterruptedException,
            URISyntaxException, CasambiSimpleException, TimeoutException, ExecutionException {
        final URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/units");
        final ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getUnitList", url, response);

        final Gson gson = new Gson();
        final Type unitMapType = new TypeToken<Map<String, CasambiSimpleMessageUnit>>() {
        }.getType();
        final Map<String, CasambiSimpleMessageUnit> unitList = gson.fromJson(response.getContentAsString(),
                unitMapType);
        return unitList;
    }

    /**
     * getUnitState returns state information for a single unit
     *
     * @param unitId as assigned by the Casambi system
     * @return unitState information
     * @throws IOException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    public @Nullable CasambiSimpleMessageUnit getUnitState(int unitId) throws IOException, InterruptedException,
            URISyntaxException, CasambiSimpleException, TimeoutException, ExecutionException {
        final URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/units/" + unitId + "/state");
        final ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getUnitState", url, response);

        final Gson gson = new Gson();
        final CasambiSimpleMessageUnit unitState = gson.fromJson(response.getContentAsString(),
                CasambiSimpleMessageUnit.class);
        return unitState;
    }

    /**
     * getScenes returns information about the scenes defined in the Casambi network
     *
     * @return structure with scenes and information about the scenes
     * @throws IOException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    public @Nullable Map<String, CasambiSimpleMessageScene> getScenes() throws IOException, InterruptedException,
            URISyntaxException, CasambiSimpleException, TimeoutException, ExecutionException {
        final URL url = new URL(casaServer, "/v1/networks/" + casambiNetworkId + "/scenes");
        final ContentResponse response = makeHttpGet(url).send();
        checkHttpResponse("getScenes", url, response);

        final Gson gson = new Gson();
        final Type sceneMapType = new TypeToken<Map<String, CasambiSimpleMessageScene>>() {
        }.getType();
        final Map<String, CasambiSimpleMessageScene> scenes = gson.fromJson(response.getContentAsString(),
                sceneMapType);
        return scenes;
    }

    /**
     * getFixtureInfo gets detailed information about a single fixture
     *
     * @param fixtureId as defined by the Casambi system
     * @return fixture information. FIXME: convert to message object
     * @throws IOException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws CasambiSimpleException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    public JsonObject getFixtureInfo(int fixtureId) throws IOException, InterruptedException, URISyntaxException,
            CasambiSimpleException, TimeoutException, ExecutionException {
        final URL url = new URL(casaServer, "/v1/fixtures/" + fixtureId);
        final ContentResponse response = makeHttpGet(url).send();

        checkHttpResponse("getFixtureInfo", url, response);

        final JsonObject fixtureInfo = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
        return fixtureInfo;
    }
}
