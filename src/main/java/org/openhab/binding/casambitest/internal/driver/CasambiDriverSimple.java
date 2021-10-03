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

package org.openhab.binding.casambitest.internal.driver;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.collections4.QueueUtils;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.uuid.Generators;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link CasambiDriverSimple} provides the interface to Casambi websocket API.
 *
 * @author Hein Osenberg - Initial contribution
 *
 *         Based on casambi-master by Olof Hellquist https://github.com/awahlig/casambi-master
 *
 * @version V0.1 210827@hpo First version, setup IDE
 * @version V0.2 210829@hpo All methods from casambi-master implemented, exception, logging added
 * @version V0.3 210918@hpo Integration into the openHAB ecosystem
 *
 */
@NonNullByDefault
public class CasambiDriverSimple {

    // Connection parameters

    private URL casaServer;
    private String userId;
    private String userPassword;
    private String networkPassword;
    private String apiKey;

    // Connection status

    private @Nullable WebSocket casa_socket;

    private String networkId;
    private String sessionId;
    private int wireId; // FIXME: Make this configurable

    final Logger logger = LoggerFactory.getLogger(CasambiDriverSimple.class);

    private Queue<String> ringBuffer = QueueUtils.synchronizedQueue(new CircularFifoQueue<String>(128));

    public class CasambiException extends Exception {
        final static long serialVersionUID = 210829110214L; // Use dateTime
        public String message;

        public CasambiException(String msg) {
            super(msg);
            message = msg;
        };
    };

    // Constructor, need developer key, email (userId id), userId and network passwords
    CasambiDriverSimple(String key, String user, String usrPw, String netPw) throws MalformedURLException {
        this.casaServer = new URL("https://door.casambi.com/");
        this.casa_socket = null;
        this.wireId = 1;
        this.networkId = "";
        this.sessionId = "";

        this.apiKey = key;
        this.userId = user;
        this.userPassword = usrPw;
        this.networkPassword = netPw;
    };

    private String ppJson(JsonObject json) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(json).toString();
    };

    private String ppJson(String json) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        // return gson.toJson(json).toString();
        JsonParser parser = new JsonParser();
        return gson.toJson(parser.parse(json));
    };

    // Creates userId session and returns session id
    public String createUserSession() throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL sessionURL = new URL(this.casaServer, "/v1/users/session");
        String payload = "{\"email\": \"" + this.userId + "\", \"password\": \"" + this.userPassword + "\"}";
        // System.out.println("Test pp: " + ppJson(payload));

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(sessionURL.toString()))
                .headers("X-Casambi-Key", this.apiKey, "Content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

        // HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("createUserSession - url: {}, got invalid status code: {}, response: {}",
                    sessionURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("createUserSession got invalid status code: " + response.statusCode()
                    + ", response: " + response.body());
        } else {
            logger.info("createUserSession - estabished successfully.");
        }

        JsonObject session = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("createUserSession: " + ppJson(session));
        this.sessionId = session.get("sessionId").getAsString();
        return this.sessionId;
    };

    // Creates network session and returns network id
    public String createNetworkSession()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL networkURL = new URL(this.casaServer, "/v1/networks/session");
        String payload = "{\"email\": \"" + this.userId + "\", \"password\": \"" + this.networkPassword + "\"}";

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(networkURL.toString()))
                .headers("X-Casambi-Key", this.apiKey, "Content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("createNetworkSession - url: {}, got invalid status code: {}, response: {}",
                    networkURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("createNetworkSession got invalid status code: " + response.statusCode()
                    + ", response: " + response.body());
        } else {
            logger.info("createNetworkSession - estabished successfully.");
        }

        JsonObject network = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("createNetworkSession: " + ppJson(network));

        Set<String> keys = network.keySet();
        this.networkId = keys.iterator().next();
        return this.networkId;
    };

    // Queries network and returns network information
    public JsonObject getNetworkInformation()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL netInfoURL = new URL(this.casaServer, "/v1/networks/" + this.networkId);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(netInfoURL.toString())).headers("X-Casambi-Key",
                this.apiKey, "X-Casambi-Session", this.sessionId, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getNetworkInformation - url: {}, got invalid status code: {}, response: {}",
                    netInfoURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("getNetworkInformation got invalid status code: " + response.statusCode()
                    + ", response: " + response.body());
        } else {
            logger.debug("getNetworkInformation - success.");
        }

        JsonObject networkInfo = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getNetworkInformation: " + ppJson(networkInfo));
        return networkInfo;
    };

    public JsonObject getNetworkState() throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.networkId + "/state");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.apiKey, "X-Casambi-Session", this.sessionId, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getNetworkState - url: {}, got invalid status code: {}, response: {}",
                    unitStateURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("getNetworkState got invalid status code: " + response.statusCode()
                    + ", response: " + response.body());
        } else {
            logger.debug("getNetworkState - success.");
        }

        JsonObject networkState = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getUnitState: " + ppJson(unitInfo));
        return networkState;
    };

    public JsonObject getNetworkDatapoints(LocalDateTime from, LocalDateTime to, int sensorType)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddhhmmss");
        String fromTime = from.format(fmt);
        String toTime = to.format(fmt);
        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.networkId + "/datapoints?sensorType="
                + sensorType + "&from=" + fromTime + "&to=" + toTime);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.apiKey, "X-Casambi-Session", this.sessionId, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getNetworkDatapoints - url: {}, got invalid status code: {}, response: {}",
                    unitStateURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("getNetworkDatapoints got invalid status code: " + response.statusCode()
                    + ", response: " + response.body());
        } else {
            logger.debug("getNetworkDataPoints - success.");
        }

        // Wrap response into object because gson does not seem to like naked json-arrays
        String res = "{\"datapoints\":" + response.body() + "}";
        // System.out.println("getNetworkDatapoints: " + res);
        JsonObject networkDataPoints = JsonParser.parseString(res).getAsJsonObject();
        return networkDataPoints;
    };

    public JsonObject getUnitList() throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.networkId + "/units");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.apiKey, "X-Casambi-Session", this.sessionId, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getUnitList - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException(
                    "getUnitList got invalid status code: " + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getUnitList - success.");
        }

        JsonObject unitList = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getUnitState: " + ppJson(unitInfo));
        return unitList;
    };

    public JsonObject getUnitState(int unitId)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.networkId + "/units/" + unitId + "/state");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.apiKey, "X-Casambi-Session", this.sessionId, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getUnitState - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException("getUnitState got invalid status code: " + response.statusCode() + ", response: "
                    + response.body());
        } else {
            logger.debug("getUnitState - success.");
        }

        JsonObject unitInfo = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getUnitState: " + ppJson(unitInfo));
        return unitInfo;
    };

    public JsonObject getScenes() throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.networkId + "/scenes");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.apiKey, "X-Casambi-Session", this.sessionId, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getScenes - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException(
                    "getScenes got invalid status code: " + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getScenes - success.");
        }

        JsonObject scenesList = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getUnitState: " + ppJson(unitInfo));
        return scenesList;
    };

    public JsonObject getFixtureInfo(int unitId)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/fixtures/" + unitId);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.apiKey, "X-Casambi-Session", this.sessionId, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getFixtureInfo - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException("getFixtureInfo got invalid status code: " + response.statusCode()
                    + ", response: " + response.body());

        } else {
            logger.debug("getFixtureInfo - success.");
        }

        JsonObject fixtureInfo = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getUnitState: " + ppJson(unitInfo));
        return fixtureInfo;
    };

    public @Nullable String receiveMessage() {
        return ringBuffer.poll();
    }

    private class WebSocketClient implements WebSocket.Listener {
        private final CountDownLatch latch;

        public WebSocketClient(CountDownLatch latch) {
            this.latch = latch;
        };

        @Override
        public void onOpen(@Nullable WebSocket webSocket) {
            logger.debug("WebSocket.onOpen called");
            WebSocket.Listener.super.onOpen(webSocket);
        };

        @Override
        public CompletionStage<?> onText(@Nullable WebSocket webSocket, @Nullable CharSequence data, boolean last) {
            logger.debug("WebSocket.onText received {}", data);
            ringBuffer.add(data.toString());
            latch.countDown();
            return WebSocket.Listener.super.onText(webSocket, data, last);
        };

        @Override
        public CompletionStage<?> onBinary(@Nullable WebSocket webSocket, @Nullable ByteBuffer data, boolean last) {
            String msg = StandardCharsets.UTF_8.decode(data).toString();
            logger.debug("onBinary received {}", ppJson(msg));
            ringBuffer.add(msg);
            latch.countDown();
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        };

        @Override
        public CompletionStage<?> onClose(@Nullable WebSocket webSocket, int statusCode, @Nullable String reason) {
            logger.debug("onClose status {}, reason {}", statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        };

        @Override
        public void onError(@Nullable WebSocket webSocket, @Nullable Throwable error) {
            logger.error("WebSocket.onError {}", webSocket.toString());
            WebSocket.Listener.super.onError(webSocket, error);
        };
    };

    public void casambiOpen() throws IOException, InterruptedException, URISyntaxException, CasambiException {

        if (this.casa_socket == null) {
            URI casambiURI = new URI("wss://door.casambi.com/v1/bridge/");

            UUID uuid1 = Generators.timeBasedGenerator().generate();

            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("method", "open");
            reqJson.addProperty("id", this.networkId);
            reqJson.addProperty("session", this.sessionId);
            reqJson.addProperty("ref", uuid1.toString());
            reqJson.addProperty("wire", this.wireId);
            reqJson.addProperty("type", 1);

            CountDownLatch latch = new CountDownLatch(1);
            Listener listener = new WebSocketClient(latch);

            this.casa_socket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(this.apiKey)
                    .buildAsync(casambiURI, listener).join();

            this.casa_socket.sendText(reqJson.toString(), true);
            logger.debug("casambiOpen: waiting for latch.");
            latch.await();

        } else {
            String msg = "casambiOpen: Error - Socket already open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
        ;
    };

    // FIXME: Socket inaktivieren (ist das richtig so?)
    public void casambiClose() throws InterruptedException, CasambiException {

        if (this.casa_socket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wireId);
            reqJson.addProperty("method", "close");

            this.casa_socket.sendText(reqJson.toString(), true);

            final int ms = 1000;
            Thread.sleep(10 * ms);
            this.casa_socket = null;
        } else {
            String msg = "casambiOpen: Error - Socket already open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
        ;
    };

    public void turnUnitOff(int unitId) throws CasambiException {
        setUnitValue(unitId, 0);
    }
    /*
     * public void turnUnitOff(int unit_id)
     * throws CasambiException {
     *
     * if(this.casa_socket != null) {
     *
     * JsonObject value = new JsonObject();
     * value.addProperty("value", 0);
     *
     * JsonObject dimmer = new JsonObject();
     * dimmer.add("Dimmer", value);
     *
     * JsonObject reqJson = new JsonObject();
     * reqJson.addProperty("wire", this.wire_id);
     * reqJson.addProperty("method", "controlUnit");
     * reqJson.addProperty("id", unit_id);
     * reqJson.add("targetControls", dimmer);
     *
     * this.casa_socket.sendText(reqJson.toString(), true);
     * } else {
     * logger.error("turnUnitOff: Error - Socket not open.");
     * throw new CasambiException();
     * };
     * };
     */

    public void turnUnitOn(int unitId) throws CasambiException {
        setUnitValue(unitId, 1);
    }

    /*
     * public void turnUnitOn(int unit_id)
     * throws CasambiException {
     *
     * if(this.casa_socket != null) {
     * JsonObject value = new JsonObject();
     * value.addProperty("value", 1);
     *
     * JsonObject dimmer = new JsonObject();
     * dimmer.add("Dimmer", value);
     *
     * JsonObject reqJson = new JsonObject();
     * reqJson.addProperty("wire", this.wire_id);
     * reqJson.addProperty("method", "controlUnit");
     * reqJson.addProperty("id", unit_id);
     * reqJson.add("targetControls", dimmer);
     *
     * this.casa_socket.sendText(reqJson.toString(), true);
     * } else {
     * logger.error("turnUnitOn: Error - Socket not open.");
     * throw new CasambiException();
     * };
     * };
     */

    public void setUnitValue(int unitId, float dim) throws CasambiException {

        if (this.casa_socket != null) {
            JsonObject value = new JsonObject();
            value.addProperty("value", dim);

            JsonObject dimmer = new JsonObject();
            dimmer.add("Dimmer", value);

            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wireId);
            reqJson.addProperty("method", "controlUnit");
            reqJson.addProperty("id", unitId);
            reqJson.add("targetControls", dimmer);

            this.casa_socket.sendText(reqJson.toString(), true);
        } else {
            String msg = "casambiOpen: Error - Socket already open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
        ;
    };

    public void turnSceneOff(int sceneId) throws CasambiException {

        if (this.casa_socket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wireId);
            reqJson.addProperty("method", "controlScene");
            reqJson.addProperty("id", sceneId);
            reqJson.addProperty("level", 0);

            this.casa_socket.sendText(reqJson.toString(), true);
        } else {
            String msg = "casambiOpen: Error - Socket already open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
        ;
    };

    public void turnSceneOn(int sceneId) throws CasambiException {

        if (this.casa_socket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wireId);
            reqJson.addProperty("method", "controlScene");
            reqJson.addProperty("id", sceneId);
            reqJson.addProperty("level", 1);

            this.casa_socket.sendText(reqJson.toString(), true);
        } else {
            String msg = "casambiOpen: Error - Socket already open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
        ;
    };
};

// END
