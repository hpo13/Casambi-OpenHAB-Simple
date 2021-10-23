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

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Type;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * @version V0.4 211017@hpo Code cleanup (functions for request building and error checks)
 */
@NonNullByDefault
public class CasambiDriverJson {

    // Connection parameters

    private @Nullable URL casaServer;
    private String userId;
    private String userPassword;
    private String networkPassword;
    private String apiKey;
    private volatile Boolean gotPong = true;

    static Boolean jsonLogActive = false;
    private static @Nullable PrintWriter writer;

    // Connection status

    private @Nullable WebSocket casambiSocket;
    private String casambiSocketStatus = "null";

    private String casambiNetworkId;
    private String casambiSessionId;
    private int casambiWireId;

    final Logger logger = LoggerFactory.getLogger(CasambiDriverJson.class);

    private LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();

    public class CasambiException extends Exception {
        final static long serialVersionUID = 210829110214L; // Use dateTime
        public String message;

        public CasambiException(String msg) {
            super(msg);
            message = msg;
        };
    };

    // Constructor, needs developer key, email (user id), user and network passwords
    public CasambiDriverJson(String key, String user, String usrPw, String netPw) {
        try {
            casaServer = new URL("https://door.casambi.com/");
        } catch (Exception e) {
            logger.error("CasambiDriverJson: exception", e);
            casaServer = null;
        }
        casambiSocket = null;
        casambiWireId = 1;
        casambiSessionId = "";
        casambiNetworkId = "";

        apiKey = key;
        userId = user;
        userPassword = usrPw;
        networkPassword = netPw;
    };

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
            dumpJsonWithMessage("+++ " + functionName + " +++", response.body());
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

    // --- Websocket section ------------------------------------------------------------------------------------------

    private class WebSocketClient implements WebSocket.Listener {

        @Override
        public void onOpen(@Nullable WebSocket webSocket) {
            logger.debug("WebSocket.onOpen called");
            dumpMessage(" +++ Socket onOpen +++");
            casambiSocketStatus = "open";
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "socketChanged");
            msg.addProperty("status", "open");
            msg.addProperty("conditon", 0);
            msg.addProperty("response", "ok");
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e);
            }
            WebSocket.Listener.super.onOpen(webSocket);
        };

        @Override
        public CompletionStage<?> onText(@Nullable WebSocket webSocket, @Nullable CharSequence data, boolean last) {
            // socket_status = "data_text";
            try {
                if (data != null && data.length() > 0) {
                    queue.put(data.toString());
                    dumpJsonWithMessage("+++ Socket onText +++", data.toString());
                } else {
                    logger.debug("onText: null message");
                }
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        };

        @Override
        public CompletionStage<?> onBinary(@Nullable WebSocket webSocket, @Nullable ByteBuffer data, boolean last) {
            String msg = StandardCharsets.UTF_8.decode(data).toString();
            // socket_status = "data_binary";
            try {
                if (msg.length() > 0) {
                    queue.put(msg);
                    dumpJsonWithMessage("+++ Socket onBinary +++", msg);
                } else {
                    logger.debug("onBinary: null message");
                }
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e);
            }
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        };

        @Override
        public CompletionStage<?> onClose(@Nullable WebSocket webSocket, int statusCode, @Nullable String reason) {
            logger.debug("onClose status {}, reason {}", statusCode, reason);
            dumpMessage("+++ Socket onClose +++");
            casambiSocketStatus = "closed";
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "socketChanged");
            msg.addProperty("status", "closed");
            msg.addProperty("conditon", statusCode);
            msg.addProperty("response", reason);
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e);
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        };

        @Override
        public void onError(@Nullable WebSocket webSocket, @Nullable Throwable error) {
            logger.error("WebSocket.onError {}", webSocket);
            dumpMessage(getTimeStamp() + " +++ Socket onError +++");
            casambiSocketStatus = "error";
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "socketChanged");
            msg.addProperty("status", "error");
            if (error != null) {
                msg.addProperty("conditon", error.hashCode());
                msg.addProperty("response", error.getMessage());
            } else {
                msg.addProperty("conditon", 0);
                msg.addProperty("response", "no message");
            }
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e);
            }
            WebSocket.Listener.super.onError(webSocket, error);
        }

    };

    public void casambiSocketOpen() throws IOException, InterruptedException, URISyntaxException, CasambiException {

        logger.debug("casambiOpen: opening socket for casambi communication");
        if (casambiSocket == null) {
            URI casambiURI = new URI("wss://door.casambi.com/v1/bridge/");

            UUID uuid = UUID.randomUUID();

            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("method", "open");
            reqJson.addProperty("id", casambiNetworkId);
            reqJson.addProperty("session", casambiSessionId);
            reqJson.addProperty("ref", uuid.toString());
            reqJson.addProperty("wire", casambiWireId);
            reqJson.addProperty("type", 1);

            // CountDownLatch latch = new CountDownLatch(1);
            Listener listener = new WebSocketClient();

            casambiSocket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(apiKey)
                    .buildAsync(casambiURI, listener).join();

            if (casambiSocket != null) {
                casambiSocket.sendText(reqJson.toString(), true);
                dumpMessage("+++ Socket casambiOpen +++");
            } else {
                logger.error("casambiOpen: failed to open socket");
            }
        } else {
            final String msg = "casambiOpen: Error - Socket already open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    };

    // FIXME: Socket inaktivieren (ist das richtig so?)
    public void casambiSocketClose() throws InterruptedException, CasambiException {

        if (jsonLogActive && writer != null) {
            dumpMessage("+++ Socket casambiClose +++");
            writer.close();
            writer = null;
        }

        if (casambiSocket != null) {
            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("wire", casambiWireId);
            reqJson.addProperty("method", "close");
            casambiSocket.sendText(reqJson.toString(), true);

            final int ms = 1000;
            CompletableFuture<WebSocket> sc = casambiSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
            sc.wait(2 * ms);
            casambiSocket = null;
        } else {
            final String msg = "casambiClose: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    };

    public void turnUnitOff(int unitId) throws CasambiException {
        setUnitValue(unitId, 0);
    }

    public void turnUnitOn(int unitId) throws CasambiException {
        setUnitValue(unitId, 1);
    }

    public void setUnitValue(int unitId, float dim) throws CasambiException {
        logger.debug("setUnitValue: unit {} value {}", unitId, dim);

        JsonObject value = new JsonObject();
        value.addProperty("value", dim);

        JsonObject dimmer = new JsonObject();
        dimmer.add("Dimmer", value);

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("wire", casambiWireId);
        reqJson.addProperty("method", "controlUnit");
        reqJson.addProperty("id", unitId);
        reqJson.add("targetControls", dimmer);

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            dumpMessage("+++ Socket setUnitValue +++");
        } else {
            final String msg = "setUnitValue: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public void turnSceneOff(int sceneId) throws CasambiException {

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("wire", casambiWireId);
        reqJson.addProperty("method", "controlScene");
        reqJson.addProperty("id", sceneId);
        reqJson.addProperty("level", 0);

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            dumpMessage("+++ Socket turnSceneOff +++");
        } else {
            final String msg = "turnSceneOff: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public void turnSceneOn(int sceneId) throws CasambiException {

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("wire", casambiWireId);
        reqJson.addProperty("method", "controlScene");
        reqJson.addProperty("id", sceneId);
        reqJson.addProperty("level", 1);

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            dumpMessage("+++ Socket turnSceneOn +++");
        } else {
            final String msg = "turnSceneOn: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public void ping() throws CasambiException {
        if (!gotPong) {
            logger.warn("ping: Response missing for last ping.");
        }
        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("wire", casambiWireId);
        reqJson.addProperty("method", "ping");

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            gotPong = false;
        } else {
            final String msg = "ping: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    // Get messages off the queue

    public @Nullable String receiveMessageJson() {
        String res = null;
        try {
            // Blocks until there is something in the queue
            res = queue.take();
        } catch (Exception e) {
            logger.error("receiveMessageJson: Exception {}", e.getMessage());
        }
        return res;
    }

    public @Nullable CasambiMessageEvent receiveMessage() {
        Gson gson = new Gson();
        String msg = receiveMessageJson();
        if (msg != null) {
            // Write Message to log file
            // dumpJsonWithMessage("+++ receiveMessage +++", msg);
            if (jsonLogActive && writer != null) {
                writer.flush();
            }
            CasambiMessageEvent event = gson.fromJson(msg, CasambiMessageEvent.class);
            // System.out.println("getUnitState: " + ppJson(unitInfo));
            return event;
        } else {
            return null;
        }
    }

    // Used by CasambiBridgeHandler

    public void pingOk() {
        gotPong = true;
    }

    // Getter for socket_status
    public String getSocketStatus() {
        return casambiSocketStatus;
    }

    public void setupJsonLogging(Boolean activate, String logPath, String logFile) {
        jsonLogActive = activate;
        if (activate) {
            try {
                // Path path = Paths.get(System.getProperty("user.home"), logPath, logFile);
                Path path = Paths.get(logPath, logFile);
                logger.debug("createUserSession: log file path is {}", path);
                writer = new PrintWriter(new FileWriter(path.toString(), true));
                writer.println("Casambi JSON message dump.");
                writer.flush();
            } catch (Exception e) {
                logger.error("createUserSessionn: Error opening JSON dump file: {}", e.toString());
                jsonLogActive = false;
                writer = null;
            }
        } else {
            writer = null;
        }
    };

    // --- JSON and logging helper routines ---------------------------------------------------------

    // Pretty print JSON object (two signatures)
    /*
     * private String ppJson(JsonObject json) {
     * Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
     * return gson.toJson(json).toString();
     * };
     */

    public String ppJson(@Nullable String json) {
        if (json != null) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            JsonObject jObj = JsonParser.parseString(json).getAsJsonObject();
            return gson.toJson(jObj);
        } else {
            return "";
        }
    };

    // Write JSON object to log file (two signatures)
    /*
     * private void dumpJson(JsonObject json) {
     * try {
     * if (debug && writer != null) {
     * writer.println(ppJson(json));
     * }
     * } catch (Exception e) {
     * logger.error("dumpJson: Error dumping JSON: {}", e.toString());
     * }
     * }
     */

    private void dumpMessage(String msg) {
        if (jsonLogActive && writer != null) {
            writer.println(getTimeStamp() + " " + msg);
        }
    }

    private void dumpJsonWithMessage(String msg, @Nullable String json) {
        // logger.debug("dumpJsonWithMessage: {} - {}", msg, json);
        if (jsonLogActive && writer != null) {
            writer.println(getTimeStamp() + " '" + msg + "'");
            if (json != null) {
                dumpJson(json);
            } else {
                logger.debug("dumJsonWithMessage: got null json. message was '{}'", msg);
            }
        }
    }

    private void dumpJson(@Nullable String json) {
        try {
            if (jsonLogActive && writer != null && json != null) {
                String jStr = ppJson(json);
                // if (jStr != null) {
                writer.println(jStr);
                // } else {
                // logger.info("ppJson: got null string for json: {}", json);
                // writer.println("=== ERROR === ppJson Got null string for json: " + json);
                // }
            }
        } catch (Exception e) {
            logger.warn("dumpJson: Exception dumping JSON: {}", e.toString());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.debug(sw.toString());
        }
    }

    private String getTimeStamp() {
        final DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("dd.MM.YY HH:mm:ss");
        return LocalDateTime.now().format(myFormatObj);
    }

}

// END
