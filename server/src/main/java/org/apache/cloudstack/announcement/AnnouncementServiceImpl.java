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

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.command.user.announcement.CreateAnnouncementCmd;
import org.apache.cloudstack.api.command.user.announcement.DeleteAnnouncementCmd;
import org.apache.cloudstack.api.command.user.announcement.ListAnnouncementsCmd;
import org.apache.cloudstack.api.command.user.announcement.UpdateAnnouncementCmd;
import org.apache.cloudstack.api.response.AnnouncementResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.announcement.dao.AnnouncementDao;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class AnnouncementServiceImpl implements AnnouncementService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    AnnouncementDao announcementDao;

    @Inject
    AccountManager accountManager;

    @Inject
    AnnouncementSseManager announcementSseManager;

    @Inject
    ResponseGenerator responseGenerator;

    @Override
    public ListResponse<AnnouncementResponse> listAnnouncements(ListAnnouncementsCmd cmd) {
        Pair<List<AnnouncementVO>, Integer> result = listAnnouncementsInternal(cmd);
        ListResponse<AnnouncementResponse> response = new ListResponse<>();
        List<AnnouncementResponse> responses = result.first().stream()
                .map(responseGenerator::createAnnouncementResponse)
                .collect(Collectors.toList());
        response.setResponses(responses, result.second());
        return response;
    }

    private Pair<List<AnnouncementVO>, Integer> listAnnouncementsInternal(ListAnnouncementsCmd cmd) {
        Long callerAccountId = CallContext.current().getCallingAccountId();
        boolean rootAdmin = callerAccountId != null && callerAccountId != Account.ACCOUNT_ID_SYSTEM && accountManager.isRootAdmin(callerAccountId);

        List<AnnouncementVO> announcements;
        if (rootAdmin) {
            announcements = announcementDao.listAllAnnouncements();
        } else {
            announcements = announcementDao.listVisibleAnnouncements(new Date());
        }

        if (cmd.getId() != null) {
            announcements = announcements.stream().filter(vo -> vo.getId() == cmd.getId()).collect(Collectors.toList());
        }
        if (StringUtils.isNotBlank(cmd.getType())) {
            String type = normalizeType(cmd.getType());
            announcements = announcements.stream().filter(vo -> type.equals(vo.getType())).collect(Collectors.toList());
        }
        return new Pair<>(announcements, announcements.size());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ANNOUNCEMENT_CREATE, eventDescription = "Creating announcement")
    public Announcement createAnnouncement(CreateAnnouncementCmd cmd) {
        String title = cmd.getTitle();
        String message = cmd.getMessage();
        String type = normalizeType(cmd.getType());
        boolean enabled = cmd.getEnabled() == null || cmd.getEnabled();
        boolean closable = cmd.getClosable() == null || cmd.getClosable();
        boolean persistDismissal = cmd.getPersistDismissal() == null || cmd.getPersistDismissal();
        Date startDate = cmd.getStartDate();
        Date endDate = cmd.getEndDate();
        int priority = cmd.getPriority() == null ? 0 : cmd.getPriority();

        CallContext.current().setEventDetails(String.format("Title: %s, Type: %s", title, type));
        validateParameters(title, message, type, startDate, endDate);

        AnnouncementVO announcementVO = new AnnouncementVO(title, message, type, enabled, closable, persistDismissal, startDate, endDate, priority);
        announcementDao.persist(announcementVO);
        logger.info("Created announcement [{}].", announcementVO);
        announcementSseManager.pushUpdate();
        return announcementVO;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ANNOUNCEMENT_UPDATE, eventDescription = "Updating announcement")
    public Announcement updateAnnouncement(UpdateAnnouncementCmd cmd) {
        Long announcementId = cmd.getId();
        AnnouncementVO announcementVO = announcementDao.findById(announcementId);
        if (announcementVO == null) {
            throw new CloudRuntimeException(String.format("Failed to find announcement with ID %s.", announcementId));
        }

        CallContext.current().setEventDetails(String.format("ID: %s", announcementId));

        String type = cmd.getType() == null ? null : normalizeType(cmd.getType());
        validateParameters(cmd.getTitle(), cmd.getMessage(), type, cmd.getStartDate(), cmd.getEndDate());

        if (cmd.getTitle() != null) {
            announcementVO.setTitle(cmd.getTitle());
        }
        if (cmd.getMessage() != null) {
            announcementVO.setMessage(cmd.getMessage());
        }
        if (type != null) {
            announcementVO.setType(type);
        }
        if (cmd.getEnabled() != null) {
            announcementVO.setEnabled(cmd.getEnabled());
        }
        if (cmd.getClosable() != null) {
            announcementVO.setClosable(cmd.getClosable());
        }
        if (cmd.getPersistDismissal() != null) {
            announcementVO.setPersistDismissal(cmd.getPersistDismissal());
        }
        if (cmd.getStartDate() != null) {
            announcementVO.setStartDate(cmd.getStartDate());
        }
        if (cmd.getEndDate() != null) {
            announcementVO.setEndDate(cmd.getEndDate());
        }
        if (cmd.getPriority() != null) {
            announcementVO.setPriority(cmd.getPriority());
        }

        validateParameters(announcementVO.getTitle(), announcementVO.getMessage(), announcementVO.getType(),
                announcementVO.getStartDate(), announcementVO.getEndDate());

        announcementVO.setUpdated(new Date());
        announcementDao.update(announcementId, announcementVO);
        logger.info("Updated announcement [{}].", announcementVO);
        announcementSseManager.pushUpdate();
        return announcementDao.findById(announcementId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ANNOUNCEMENT_REMOVE, eventDescription = "Deleting announcement")
    public void deleteAnnouncement(DeleteAnnouncementCmd cmd) {
        Long announcementId = cmd.getId();
        AnnouncementVO announcementVO = announcementDao.findById(announcementId);
        CallContext.current().setEventDetails(String.format("ID: %s", announcementId));

        if (announcementVO == null) {
            throw new CloudRuntimeException(String.format("Failed to find announcement with ID %s.", announcementId));
        }
        announcementDao.remove(announcementId);
        logger.info("Deleted announcement [{}].", announcementVO);
        announcementSseManager.pushUpdate();
    }

    /**
     * Validates the parameters of an announcement. Null title/message/type mean "not provided" (update case).
     */
    protected void validateParameters(String title, String message, String type, Date startDate, Date endDate) {
        if (title != null && StringUtils.isBlank(title)) {
            throw new CloudRuntimeException("Announcement title cannot be blank.");
        }
        if (message != null && StringUtils.isBlank(message)) {
            throw new CloudRuntimeException("Announcement message cannot be blank.");
        }
        if (type != null && !AnnouncementConstants.VALID_TYPES.contains(type)) {
            throw new CloudRuntimeException(String.format("Invalid announcement type %s. Valid types are: %s.", type, AnnouncementConstants.VALID_TYPES));
        }
        if (startDate != null && endDate != null && !startDate.before(endDate)) {
            throw new CloudRuntimeException("Announcement start date must be before the end date.");
        }
        if (StringUtils.isNotBlank(message) && message.length() > 4095) {
            throw new CloudRuntimeException("Announcement message must not exceed 4095 characters.");
        }
    }

    /**
     * Normalizes the provided type: defaults to info when blank and maps the danger alias to error.
     */
    protected String normalizeType(String type) {
        if (StringUtils.isBlank(type)) {
            return AnnouncementConstants.TYPE_INFO;
        }
        String normalized = type.trim().toLowerCase();
        if (AnnouncementConstants.TYPE_DANGER_ALIAS.equals(normalized)) {
            return AnnouncementConstants.TYPE_ERROR;
        }
        return normalized;
    }
}
