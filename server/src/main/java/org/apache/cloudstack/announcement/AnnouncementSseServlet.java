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

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Server-sent events endpoint streaming the currently visible announcements to the UI at
 * {@code /client/announcements/events}. The payload only contains data that is also served by the
 * unauthenticated {@code listAnnouncements} API, therefore no extra authentication is required.
 */
public class AnnouncementSseServlet extends HttpServlet {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final long WAIT_INTERVAL_MS = 5000;

    private volatile AnnouncementSseManager announcementSseManager;

    @Override
    public void init(final ServletConfig config) throws ServletException {
        WebApplicationContext webApplicationContext = WebApplicationContextUtils.getWebApplicationContext(config.getServletContext());
        if (webApplicationContext != null) {
            announcementSseManager = webApplicationContext.getBean(AnnouncementSseManager.class);
        }
        if (announcementSseManager == null) {
            logger.warn("AnnouncementSseManager bean not found; the announcement SSE endpoint will be unavailable.");
            throw new ServletException("AnnouncementSseManager bean not found.");
        }
        logger.info("Announcement SSE servlet initialized.");
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");

        AnnouncementSseManager manager = announcementSseManager;
        if (manager == null) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Announcement service is not ready yet.");
            return;
        }

        resp.flushBuffer();

        AnnouncementSseManager.ClientConnection connection = new AnnouncementSseManager.ClientConnection(resp);
        manager.registerClient(connection);
        manager.sendCurrentStateToClient(connection);
        logger.debug("New announcement SSE connection from {}.", req.getRemoteAddr());
        try {
            while (!connection.isClosed()) {
                try {
                    Thread.sleep(WAIT_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            manager.unregisterClient(connection);
        }
    }
}
