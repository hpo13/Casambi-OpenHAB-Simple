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
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * CasambiSimpleDriverLogger - logs messages from the Casambi server, used for debugging and development
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDriverLogger {

    public @Nullable PrintWriter writer;
    private Boolean logActive = false;
    private String logPath;
    private String logFile;

    private Timer timer = new Timer();
    private Calendar calendar = Calendar.getInstance();

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleDriverLogger.class);

    /**
     * Contstructor, sets up the logger for the messages received from the Casambi server. Messages can be logged for
     * debugging and development purposes
     *
     * @param activate - logger is only set up, if active is true, otherwise the Casambi messages are not logged
     * @param logPath - directory path for the log file (must be writable by openhab)
     * @param logFile - file name for the log file
     *            FIXME: Prevent log file from growing infinitely (e.g. daily restart of the log, allow for logrotate)
     */
    public CasambiSimpleDriverLogger(Boolean activate, String path, String file) {
        logActive = activate;
        logPath = path;
        logFile = file;

        writer = null;
        if (logActive) {
            writer = open(logPath, logFile);
            scheduleRotate();
        }
    }

    // --- JSON and logging helper routines ---------------------------------------------------------

    /**
     * dumpMessage writes a string to the log (with timestamp)
     *
     * @param msg - string to be written to the log
     */
    public void dumpMessage(String msg) {
        if (writer != null) {
            writer.println(getTimeStamp() + " " + msg);
        }
    }

    /**
     * dumpJsonWithMessage writes a string and a formatted Json record to the log (with timestamp)
     *
     * @param msg - string to be written
     * @param json - json record as string (will be prettyprinted)
     */
    public void dumpJsonWithMessage(String msg, @Nullable String json) {
        if (writer != null) {
            writer.println(getTimeStamp() + " '" + msg + "'");
            if (json != null) {
                dumpJson(json);
            }
        }
    }

    /**
     * flushes the log
     */
    public void flush() {
        if (writer != null) {
            writer.flush();
        }
    }

    public @Nullable PrintWriter open(String logPath, String logFile) {
        final SimpleDateFormat formatter = new SimpleDateFormat("yyMMdd");
        PrintWriter writerLocal = null;
        try {
            Path path = Paths.get(logPath, formatter.format(new Date()) + "_" + logFile);
            logger.debug("CasambiSimpleDriverLogger: log file path is {}", path);
            writerLocal = new PrintWriter(new FileWriter(path.toString(), true));
            writerLocal.println(getTimeStamp() + " Casambi JSON message dump opened.");
            writer = writerLocal;
            flush();
        } catch (Exception e) {
            logger.error("CasambiSimpleDriverLogger: Error opening JSON dump file: {}", e.toString());
        }
        return writerLocal;
    }

    /**
     * close writes a message and then closes the log
     */
    public void close() {
        PrintWriter writerLocal = writer;
        if (writerLocal != null) {
            dumpMessage(getTimeStamp() + " ++++ Socket casambiClose +++");
            flush();
            writerLocal.close();
            writer = null;
        }
    }

    public void scheduleRotate() {
        calendar.getTime();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR, 0);
        calendar.set(Calendar.MINUTE, 1);
        calendar.set(Calendar.SECOND, 0);
        logger.info("RotateLog: next log rotation {}", calendar);
        timer.schedule(new RotateLog(), calendar.getTime());
    }

    /**
     * RotateLog closes the current log file and reopens a new log file every day at 00:01
     *
     */
    private class RotateLog extends TimerTask {
        @Override
        public void run() {
            dumpMessage(getTimeStamp() + "--- Log rotate should happen here");
            timer.cancel();
            // FIXME: close and reopen log file here
            close();
            writer = open(logPath, logFile);
            scheduleRotate();
        }
    }

    /**
     * ppJson prettyprints a Json string for output to the log
     *
     * @param json - json string
     * @return the prettyprinted string
     */
    private String ppJson(@Nullable String json) {
        if (json != null) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            JsonObject jObj = JsonParser.parseString(json).getAsJsonObject();
            return gson.toJson(jObj);
        } else {
            return "";
        }
    }

    /**
     * dumpJson prettyprints a json string and writes it to the log
     *
     * @param json - json string
     */
    private void dumpJson(@Nullable String json) {
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

    /**
     * getTimeStamp generates and formats a timestamp
     *
     * @return timestamp (as string)
     */
    private String getTimeStamp() {
        final DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("dd.MM.YY HH:mm:ss");
        return LocalDateTime.now().format(myFormatObj);
    }
}
