// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.announcement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;

import org.apache.cloudstack.announcement.dao.AnnouncementDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Manages the server-sent events connections of browsers waiting for announcement updates and pushes
 * the current list of visible announcements to them whenever it changes (locally or on another
 * management server, detected by the periodic fingerprint check).
 */
@Component
public class AnnouncementSseManager {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final int HEARTBEAT_INTERVAL_SECONDS = 60;
    private static final String KEEP_ALIVE_EVENT = ": keep-alive\n\n";

    @Inject
    AnnouncementDao announcementDao;

    private final CopyOnWriteArrayList<ClientConnection> clients = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Announcement-SSE-Heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private volatile String lastFingerprint = null;

    private final Gson gson = new Gson();

    public static class ClientConnection {
        private final HttpServletResponse response;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public ClientConnection(HttpServletResponse response) {
            this.response = response;
        }

        public boolean isClosed() {
            return closed.get();
        }

        public void close() {
            closed.set(true);
        }

        public HttpServletResponse getResponse() {
            return response;
        }
    }

    @PostConstruct
    public void start() {
        heartbeatExecutor.scheduleWithFixedDelay(this::checkForUpdates, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.debug("Announcement SSE manager started with a heartbeat interval of {} seconds.", HEARTBEAT_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void stop() {
        heartbeatExecutor.shutdownNow();
        for (ClientConnection client : clients) {
            client.close();
        }
        clients.clear();
    }

    public void registerClient(ClientConnection client) {
        clients.add(client);
        logger.trace("Registered announcement SSE client; {} connected client(s).", clients.size());
    }

    public void unregisterClient(ClientConnection client) {
        client.close();
        clients.remove(client);
        logger.trace("Unregistered announcement SSE client; {} connected client(s).", clients.size());
    }

    public int getConnectedClients() {
        return clients.size();
    }

    /**
     * Sends the current visible announcements to the given client; called when a client connects.
     */
    public void sendCurrentStateToClient(ClientConnection client) {
        writeToClient(client, buildAnnouncementEvent());
    }

    /**
     * Pushes the current visible announcements to all connected clients, if the state changed since the last push.
     */
    public synchronized void pushUpdate() {
        String fingerprint = buildFingerprint(announcementDao.listVisibleAnnouncements(new Date()));
        if (fingerprint.equals(lastFingerprint)) {
            logger.trace("Announcement state unchanged; skipping push.");
            return;
        }
        lastFingerprint = fingerprint;
        String event = buildAnnouncementEvent();
        for (ClientConnection client : clients) {
            writeToClient(client, event);
        }
    }

    /**
     * Periodic task: sends a keep-alive to detect dead connections and picks up changes done on
     * other management servers of the cluster.
     */
    protected void checkForUpdates() {
        try {
            for (ClientConnection client : clients) {
                writeToClient(client, KEEP_ALIVE_EVENT);
            }
            pushUpdate();
        } catch (Exception exception) {
            logger.warn("Failed to run the announcement heartbeat check: {}", exception.getMessage(), exception);
        }
    }

    private String buildAnnouncementEvent() {
        String payload = buildPayload(announcementDao.listVisibleAnnouncements(new Date()));
        return "event: announcement\ndata: " + payload + "\n\n";
    }

    private void writeToClient(ClientConnection client, String event) {
        if (client.isClosed()) {
            return;
        }
        try {
            synchronized (client) {
                HttpServletResponse response = client.getResponse();
                response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().flush();
            }
        } catch (IOException | IllegalStateException exception) {
            logger.debug("Announcement SSE client disconnected: {}", exception.getMessage());
            unregisterClient(client);
        }
    }

    private String buildFingerprint(List<AnnouncementVO> visible) {
        return visible.stream()
                .map(vo -> String.format("%s:%s", vo.getUuid(), vo.getUpdated() == null ? 0L : vo.getUpdated().getTime()))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private String buildPayload(List<AnnouncementVO> visible) {
        JsonObject payload = new JsonObject();
        JsonArray array = new JsonArray();
        for (AnnouncementVO announcement : visible) {
            JsonObject json = new JsonObject();
            json.addProperty("id", announcement.getUuid());
            json.addProperty("title", announcement.getTitle());
            json.addProperty("message", announcement.getMessage());
            json.addProperty("type", announcement.getType());
            json.addProperty("priority", announcement.getPriority());
            json.addProperty("closable", announcement.getClosable());
            json.addProperty("persistDismissal", announcement.getPersistDismissal());
            json.addProperty("startDate", announcement.getStartDate() == null ? null : announcement.getStartDate().getTime());
            json.addProperty("endDate", announcement.getEndDate() == null ? null : announcement.getEndDate().getTime());
            array.add(json);
        }
        payload.add("announcements", array);
        return gson.toJson(payload);
    }
}
