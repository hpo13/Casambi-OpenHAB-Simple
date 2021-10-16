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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Type;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageEvent;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetwork;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageSession;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnitState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.uuid.Generators;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

/**
 * Casambi driver - interface to Casambi websocket API
 *
 * Based on casambi-master by Olof Hellquist https://github.com/awahlig/casambi-master
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 * @version V0.2 210927@hpo Rewrite with POJOs, add @Nullable annotation
 * @version V0.3 211010@hpo Ping added
 *
 */
@NonNullByDefault
public class CasambiDriverJson {

    // Connection parameters

    private URL casaServer;
    private String user;
    private String user_password;
    private String network_password;
    private String api_key;
    private Boolean gotPong = true;
    private static @Nullable PrintWriter writer;

    // Connection status

    private @Nullable WebSocket casa_socket;
    private String socket_status = "null";

    private String network_id;
    private String session_id;
    private int wire_id; // FIXME: Make this configurable

    final Logger logger = LoggerFactory.getLogger(CasambiDriverJson.class);

    final static String path = "/home/localhpo/eclipse-workspace/org.hposenberg.casambi-driver/testdata/";
    final static String sessionTest = "driver_messages.txt";

    private LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();

    public class CasambiException extends Exception {
        final static long serialVersionUID = 210829110214L; // Use dateTime
        public String message;

        public CasambiException(String msg) {
            super(msg);
            message = msg;
        };
    };

    // Constructor, need developer key, email (user id), user and network passwords
    public CasambiDriverJson(String key, String user, String usr_pw, String net_pw) throws MalformedURLException {
        this.casaServer = new URL("https://door.casambi.com/");
        this.casa_socket = null;
        this.wire_id = 1;
        this.session_id = "";
        this.network_id = "";

        this.api_key = key;
        this.user = user;
        this.user_password = usr_pw;
        this.network_password = net_pw;
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

    private void dumpJson(JsonObject json) {
        try {
            writer.println(ppJson(json));
        } catch (Exception e) {
            logger.error("dumpJson: Error dumping JSON: {}", e.toString());
        }
    }

    private void dumpJson(String json) {
        try {
            writer.println(ppJson(json));
        } catch (Exception e) {
            logger.error("dumpJson: Error dumping JSON: {}", e.toString());
        }
    }

    // Creates user session and returns session info
    public CasambiMessageSession createUserSession()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        try {
            writer = new PrintWriter(new File(path + sessionTest));
            writer.println("Casambi JSON message dump.");
        } catch (Exception e) {
            logger.error("createUserSessionn: Error opening JSON dump file: {}", e.toString());
        }

        URL sessionURL = new URL(this.casaServer, "/v1/users/session");
        String payload = "{\"email\": \"" + this.user + "\", \"password\": \"" + this.user_password + "\"}";
        // logger.debug("createUserSession: payload {}, key {}", payload, this.api_key);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(sessionURL.toString()))
                .headers("X-Casambi-Key", this.api_key, "Content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("createUserSession - url: {}, got invalid status code: {}, response: {}",
                    sessionURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("createUserSession - url:" + sessionURL.toString()
                    + ", got invalid status code: " + response.statusCode() + ", response: " + response.body());
        } else {
            logger.info("createUserSession - estabished successfully.");
        }
        writer.println(getTimeStamp() + " +++ createUserSession +++");
        dumpJson(response.body());

        Gson gson = new Gson();
        CasambiMessageSession sessObj = gson.fromJson(response.body(), CasambiMessageSession.class);
        this.session_id = sessObj.sessionId;
        return sessObj;
    };

    // Creates network session and returns network info
    public Map<String, CasambiMessageNetwork> createNetworkSession()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL networkURL = new URL(this.casaServer, "/v1/networks/session");
        String payload = "{\"email\": \"" + this.user + "\", \"password\": \"" + this.network_password + "\"}";

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(networkURL.toString()))
                .headers("X-Casambi-Key", this.api_key, "Content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("createNetworkSession - url: {}, got invalid status code: {}, response: {}",
                    networkURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("createNetworkSession - url:" + networkURL.toString()
                    + ", got invalid status code: " + response.statusCode() + ", response: " + response.body());
        } else {
            logger.info("createNetworkSession - estabished successfully.");
        }
        writer.println(getTimeStamp() + " +++ createNetworkSession +++");
        dumpJson(response.body());

        Gson gson = new Gson();
        Type networkMapType = new TypeToken<Map<String, CasambiMessageNetwork>>() {
        }.getType();
        Map<String, CasambiMessageNetwork> networks = gson.fromJson(response.body(), networkMapType);
        for (CasambiMessageNetwork network : networks.values()) {
            this.network_id = network.id;
        }
        return networks;
    };

    // Queries network and returns network information
    public JsonObject getNetworkInformation()
            throws URISyntaxException, IOException, InterruptedException, CasambiException {

        URL netInfoURL = new URL(this.casaServer, "/v1/networks/" + this.network_id);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(netInfoURL.toString())).headers("X-Casambi-Key",
                this.api_key, "X-Casambi-Session", this.session_id, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getNetworkInformation - url: {}, got invalid status code: {}, response: {}",
                    netInfoURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("getNetworkInformation - url:" + netInfoURL.toString()
                    + ", got invalid status code: " + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getNetworkInformation - success.");
        }
        writer.println(getTimeStamp() + " +++ getNetworkInformation +++");
        dumpJson(response.body());

        JsonObject networkInfo = JsonParser.parseString(response.body()).getAsJsonObject();
        return networkInfo;
    };

    public @Nullable CasambiMessageNetworkState getNetworkState()
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.network_id + "/state");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.api_key, "X-Casambi-Session", this.session_id, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getNetworkState - url: {}, got invalid status code: {}, response: {}",
                    unitStateURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("getNetworkState - url:" + unitStateURL.toString()
                    + ", got invalid status code: " + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getNetworkState - success.");
        }
        writer.println(getTimeStamp() + " +++ getNetworkState +++");
        dumpJson(response.body());

        Gson gson = new Gson();
        CasambiMessageNetworkState networkState = gson.fromJson(response.body(), CasambiMessageNetworkState.class);
        return networkState;
    };

    public JsonObject getNetworkDatapoints(LocalDateTime from, LocalDateTime to, int sensor_type)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddhhmmss");
        String fromTime = from.format(fmt);
        String toTime = to.format(fmt);
        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.network_id + "/datapoints?sensorType="
                + sensor_type + "&from=" + fromTime + "&to=" + toTime);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.api_key, "X-Casambi-Session", this.session_id, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getNetworkDatapoints - url: {}, got invalid status code: {}, response: {}",
                    unitStateURL.toString(), response.statusCode(), response.body());
            throw new CasambiException("getNetworkDatapoints - url:" + unitStateURL.toString()
                    + ", got invalid status code: " + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getNetworkDataPoints - success.");
        }
        writer.println(getTimeStamp() + " +++ getNetworkDatapoints +++");
        dumpJson(response.body());

        // Wrap response into object because gson does not seem to like naked json-arrays
        String res = "{\"datapoints\":" + response.body() + "}";
        JsonObject networkDataPoints = JsonParser.parseString(res).getAsJsonObject();
        return networkDataPoints;
    };

    public @Nullable Map<String, CasambiMessageUnit> getUnitList()
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.network_id + "/units");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.api_key, "X-Casambi-Session", this.session_id, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getUnitList - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException("getUnitList - url:" + unitStateURL.toString() + ", got invalid status code: "
                    + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getUnitList - success.");
        }
        writer.println(getTimeStamp() + " +++ getUnitList +++");
        dumpJson(response.body());

        Gson gson = new Gson();
        Type unitMapType = new TypeToken<Map<String, CasambiMessageUnit>>() {
        }.getType();
        Map<String, CasambiMessageUnit> unitList = gson.fromJson(response.body(), unitMapType);
        return unitList;
    };

    public @Nullable CasambiMessageUnitState getUnitState(int unit_id)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.network_id + "/units/" + unit_id + "/state");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.api_key, "X-Casambi-Session", this.session_id, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getUnitState - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException("getUnitState - url:" + unitStateURL.toString() + ", got invalid status code: "
                    + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getUnitState - success.");
        }
        writer.println(getTimeStamp() + " +++ getUnitState +++");
        dumpJson(response.body());

        Gson gson = new Gson();
        CasambiMessageUnitState unitState = gson.fromJson(response.body(), CasambiMessageUnitState.class);
        return unitState;
    };

    public @Nullable Map<String, CasambiMessageScene> getScenes()
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/networks/" + this.network_id + "/scenes");

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.api_key, "X-Casambi-Session", this.session_id, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getScenes - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException("getScenes - url:" + unitStateURL.toString() + ", got invalid status code: "
                    + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getScenes - success.");
        }
        writer.println(getTimeStamp() + " +++ getScenes +++");
        dumpJson(response.body());

        Gson gson = new Gson();
        Type sceneMapType = new TypeToken<Map<String, CasambiMessageScene>>() {
        }.getType();
        Map<String, CasambiMessageScene> scenes = gson.fromJson(response.body(), sceneMapType);
        return scenes;
    };

    public JsonObject getFixtureInfo(int unit_id)
            throws IOException, InterruptedException, URISyntaxException, CasambiException {

        URL unitStateURL = new URL(this.casaServer, "/v1/fixtures/" + unit_id);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI(unitStateURL.toString())).headers("X-Casambi-Key",
                this.api_key, "X-Casambi-Session", this.session_id, "Content-type", "application/json").GET().build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("getFixtureInfo - url: {}, got invalid status code: {}, response: {}", unitStateURL.toString(),
                    response.statusCode(), response.body());
            throw new CasambiException("getFixtureInfo - url:" + unitStateURL.toString() + ", got invalid status code: "
                    + response.statusCode() + ", response: " + response.body());
        } else {
            logger.debug("getFixtureInfo - success.");
        }
        writer.println(getTimeStamp() + " +++ getFixtureInfo +++");
        dumpJson(response.body());

        JsonObject fixtureInfo = JsonParser.parseString(response.body()).getAsJsonObject();
        // System.out.println("getUnitState: " + ppJson(unitInfo));
        return fixtureInfo;
    };

    public @Nullable String receiveMessageJson() {
        String res = null;
        try {
            // Blocks until there is something in the queue
            res = queue.take();
        } catch (Exception e) {
            logger.error("receiveMessageJson: Exception {}", e);
        }
        return res;
    }

    public @Nullable CasambiMessageEvent receiveMessage() {
        Gson gson = new Gson();
        String msg = receiveMessageJson();
        if (msg != null) {
            writer.println(getTimeStamp() + " +++ receiveMessage +++");
            dumpJson(msg);
            writer.flush();
            CasambiMessageEvent event = gson.fromJson(msg, CasambiMessageEvent.class);
            // System.out.println("getUnitState: " + ppJson(unitInfo));
            return event;
        } else {
            return null;
        }
    }

    private class WebSocketClient implements WebSocket.Listener {

        @Override
        public void onOpen(@Nullable WebSocket webSocket) {
            logger.debug("WebSocket.onOpen called");
            writer.println(getTimeStamp() + " +++ Socket onOpen +++");
            socket_status = "open";
            WebSocket.Listener.super.onOpen(webSocket);
        };

        @Override
        public CompletionStage<?> onText(@Nullable WebSocket webSocket, @Nullable CharSequence data, boolean last) {
            // logger.debug("WebSocket.onText received {}", data);
            // socket_status = "data_text";
            writer.println(getTimeStamp() + " +++ Socket onText +++");
            writer.append(data);
            try {
                queue.put(data.toString());
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        };

        @Override
        public CompletionStage<?> onBinary(@Nullable WebSocket webSocket, @Nullable ByteBuffer data, boolean last) {
            String msg = StandardCharsets.UTF_8.decode(data).toString();
            // logger.debug("onBinary received {}", ppJson(msg));
            // socket_status = "data_binary";
            writer.println(getTimeStamp() + " +++ Socket onText +++");
            writer.append(data.asCharBuffer());
            try {
                queue.put(msg);
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e);
            }
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        };

        @Override
        public CompletionStage<?> onClose(@Nullable WebSocket webSocket, int statusCode, @Nullable String reason) {
            logger.debug("onClose status {}, reason {}", statusCode, reason);
            writer.println(getTimeStamp() + " +++ Socket onClose +++");
            socket_status = "closed";
            // push close message to ring buffer here
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        };

        @Override
        public void onError(@Nullable WebSocket webSocket, @Nullable Throwable error) {
            logger.error("WebSocket.onError {}", webSocket.toString());
            writer.println(getTimeStamp() + " +++ Socket onError +++");
            socket_status = "error";
            // push error message to ring buffer here
            WebSocket.Listener.super.onError(webSocket, error);
        }
    };

    public void casambiSocketOpen() throws IOException, InterruptedException, URISyntaxException, CasambiException {

        logger.debug("casambiOpen: opening socket for casambi communication");
        if (this.casa_socket == null) {
            URI casambiURI = new URI("wss://door.casambi.com/v1/bridge/");

            UUID uuid1 = Generators.timeBasedGenerator().generate();

            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("method", "open");
            reqJson.addProperty("id", this.network_id);
            reqJson.addProperty("session", this.session_id);
            reqJson.addProperty("ref", uuid1.toString());
            reqJson.addProperty("wire", this.wire_id);
            reqJson.addProperty("type", 1);

            // CountDownLatch latch = new CountDownLatch(1);
            Listener listener = new WebSocketClient();

            this.casa_socket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(this.api_key)
                    .buildAsync(casambiURI, listener).join();

            this.casa_socket.sendText(reqJson.toString(), true);
            writer.println(getTimeStamp() + " +++ Socket casambiOpen +++");

        } else {
            final String msg = "casambiOpen: Error - Socket already open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    };

    // FIXME: Socket inaktivieren (ist das richtig so?)
    public void casambiSocketClose() throws InterruptedException, CasambiException {

        if (this.casa_socket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wire_id);
            reqJson.addProperty("method", "close");

            this.casa_socket.sendText(reqJson.toString(), true);
            writer.println(getTimeStamp() + " +++ Socket casambiClose +++");

            final int ms = 1000;
            Thread.sleep(10 * ms);
            this.casa_socket = null;
        } else {
            final String msg = "casambiClose: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    };

    public void turnUnitOff(int unit_id) throws CasambiException {
        setUnitValue(unit_id, 0);
    }

    public void turnUnitOn(int unit_id) throws CasambiException {
        setUnitValue(unit_id, 1);
    }

    public void setUnitValue(int unit_id, float dim) throws CasambiException {
        logger.debug("setUnitValue: unit {} value {}", unit_id, dim);
        if (this.casa_socket != null) {
            JsonObject value = new JsonObject();
            value.addProperty("value", dim);

            JsonObject dimmer = new JsonObject();
            dimmer.add("Dimmer", value);

            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wire_id);
            reqJson.addProperty("method", "controlUnit");
            reqJson.addProperty("id", unit_id);
            reqJson.add("targetControls", dimmer);

            this.casa_socket.sendText(reqJson.toString(), true);
            writer.println(getTimeStamp() + " +++ Socket setUnitValue +++");
        } else {
            final String msg = "setUnitValue: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public void turnSceneOff(int scene_id) throws CasambiException {

        if (this.casa_socket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wire_id);
            reqJson.addProperty("method", "controlScene");
            reqJson.addProperty("id", scene_id);
            reqJson.addProperty("level", 0);

            this.casa_socket.sendText(reqJson.toString(), true);
            writer.println(getTimeStamp() + " +++ Socket turnSceneOff +++");
        } else {
            final String msg = "turnSceneOff: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public void turnSceneOn(int scene_id) throws CasambiException {

        if (this.casa_socket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wire_id);
            reqJson.addProperty("method", "controlScene");
            reqJson.addProperty("id", scene_id);
            reqJson.addProperty("level", 1);

            this.casa_socket.sendText(reqJson.toString(), true);
            writer.println(getTimeStamp() + " +++ Socket turnSceneOn +++");
        } else {
            final String msg = "turnSceneOn: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public void ping() throws CasambiException {
        if (!this.gotPong) {
            logger.warn("ping: Response missing for last ping.");
        }
        if (this.casa_socket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", this.wire_id);
            reqJson.addProperty("method", "ping");

            this.casa_socket.sendText(reqJson.toString(), true);
            this.gotPong = false;
        } else {
            final String msg = "ping: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public String getSocketStatus() {
        return socket_status;
    }

    public void pingOk() {
        this.gotPong = true;
    }

    private String getTimeStamp() {
        final DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("dd.MM.YY HH:mm:ss");
        return LocalDateTime.now().format(myFormatObj);
    }
}

// END
