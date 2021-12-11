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

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Casambi driver - interface to Casambi websocket API
 *
 * Based on casambi-master by Olof Hellquist https://github.com/awahlig/casambi-master
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDriverLogger {

    private static @Nullable PrintWriter writer;

    final Logger logger = LoggerFactory.getLogger(CasambiSimpleDriverLogger.class);

    public CasambiSimpleDriverLogger(Boolean activate, String logPath, String logFile) {
        writer = null;
        PrintWriter writerLocal;
        if (activate) {
            try {
                // Path path = Paths.get(System.getProperty("user.home"), logPath, logFile);
                Path path = Paths.get(logPath, logFile);
                logger.debug("createUserSession: log file path is {}", path);
                writerLocal = new PrintWriter(new FileWriter(path.toString(), true));
                writerLocal.println(getTimeStamp() + " Casambi JSON message dump.");
                writer = writerLocal;
                flush();
            } catch (Exception e) {
                logger.error("createUserSessionn: Error opening JSON dump file: {}", e.toString());
            }
        }
    }

    // --- JSON and logging helper routines ---------------------------------------------------------

    public String ppJson(@Nullable String json) {
        if (json != null) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            JsonObject jObj = JsonParser.parseString(json).getAsJsonObject();
            return gson.toJson(jObj);
        } else {
            return "";
        }
    }

    public void dumpMessage(String msg) {
        if (writer != null) {
            writer.println(getTimeStamp() + " " + msg);
        }
    }

    public void dumpJsonWithMessage(String msg, @Nullable String json) {
        // logger.debug("dumpJsonWithMessage: {} - {}", msg, json);
        if (writer != null) {
            writer.println(getTimeStamp() + " '" + msg + "'");
            if (json != null) {
                dumpJson(json);
            }
        }
    }

    public void dumpJson(@Nullable String json) {
        try {
            PrintWriter writerLocal = writer;
            if (writerLocal != null && json != null) {
                String jStr = ppJson(json);
                writerLocal.println(jStr);
            }
        } catch (Exception e) {
            logger.warn("dumpJson: Exception dumping JSON: {}", e.toString());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.debug("{}", sw.toString());
        }
    }

    public void flush() {
        if (writer != null) {
            writer.flush();
        }
    }

    public void close() {
        PrintWriter writerLocal = writer;
        if (writerLocal != null) {
            dumpMessage(getTimeStamp() + " ++++ Socket casambiClose +++");
            flush();
            writerLocal.close();
            writer = null;
        }
    }

    private String getTimeStamp() {
        final DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("dd.MM.YY HH:mm:ss");
        return LocalDateTime.now().format(myFormatObj);
    }
}
