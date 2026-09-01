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

<template>
  <a-affix v-if="visibleAnnouncements.length > 0" class="announcement-banner-container">
    <a-alert
      v-for="announcement in visibleAnnouncements"
      :key="announcement.id"
      :type="alertType(announcement.type)"
      :show-icon="announcement.showIcon !== false"
      :closable="announcement.closable !== false"
      :banner="true"
      @close="handleClose(announcement)"
      :style="[{ border: borderColor(announcement.type) }]"
    >
      <template #message>
        <div class="banner-content" v-html="sanitize(announcement.message)" :style="[$store.getters.darkMode ? { color: 'rgba(255, 255, 255, 0.65)' } : { color: '#888' }]" />
      </template>
    </a-alert>
  </a-affix>
</template>

<script>
import DOMPurify from 'dompurify'
import { getAPI } from '@/api'

const TYPE_SEVERITY_ORDER = ['error', 'warning', 'success', 'info']

export default {
  name: 'AnnouncementBanner',
  data () {
    return {
      announcements: [],
      sessionDismissed: [],
      eventSource: null,
      now: new Date(),
      nowTimer: null
    }
  },
  computed: {
    visibleAnnouncements () {
      return [...this.announcements]
        .filter(announcement => announcement.message && this.isWithinDisplayPeriod(announcement) && !this.isDismissed(announcement))
        .sort((a, b) => {
          const priorityDiff = (a.priority || 0) - (b.priority || 0)
          if (priorityDiff !== 0) return priorityDiff
          return TYPE_SEVERITY_ORDER.indexOf(this.alertType(a.type)) - TYPE_SEVERITY_ORDER.indexOf(this.alertType(b.type))
        })
    }
  },
  mounted () {
    this.loadStaticConfig()
    this.fetchAnnouncements()
    this.openEventSource()
    this.nowTimer = setInterval(() => {
      this.now = new Date()
    }, 60 * 1000)
  },
  beforeUnmount () {
    if (this.eventSource) {
      this.eventSource.close()
      this.eventSource = null
    }
    if (this.nowTimer) {
      clearInterval(this.nowTimer)
    }
  },
  methods: {
    // Data sources
    loadStaticConfig () {
      const config = this.$config?.announcementBanner || {}
      if (!config.enabled || !config.message) {
        return
      }
      this.announcements = [{
        id: `static-${this.getHash(config.message)}`,
        message: config.message,
        type: config.type || 'info',
        closable: config.closable !== false,
        persistDismissal: config.persistDismissal !== false,
        showIcon: config.showIcon !== false,
        startDate: config.startDate,
        endDate: config.endDate,
        priority: 0
      }]
    },
    async fetchAnnouncements () {
      try {
        const json = await getAPI('listAnnouncements')
        const response = json?.listannouncementsresponse
        const items = response?.announcement || []
        if (!Array.isArray(items) || items.length === 0) {
          return
        }
        this.announcements = items.map(this.mapAnnouncement)
      } catch (error) {
        // Keep the static config banner when the API is not reachable.
      }
    },
    mapAnnouncement (item) {
      return {
        id: item.id || item.uuid,
        title: item.title,
        message: item.message,
        type: item.type || 'info',
        closable: item.closable !== false,
        persistDismissal: item.persistdismissal !== false,
        showIcon: true,
        startDate: item.startdate,
        endDate: item.enddate,
        priority: item.priority || 0
      }
    },
    openEventSource () {
      if (typeof EventSource === 'undefined') {
        return
      }
      const apiBase = this.$config.apiBase || '/client/api'
      const eventsUrl = apiBase.replace(/\/api\/?$/, '/announcements/events')
      if (!eventsUrl.endsWith('/announcements/events')) {
        return
      }
      try {
        this.eventSource = new EventSource(eventsUrl)
        this.eventSource.addEventListener('announcement', event => {
          try {
            const payload = JSON.parse(event.data)
            const items = payload?.announcements || []
            this.announcements = items.map(this.mapAnnouncement)
          } catch (error) {
            // ignore malformed payloads
          }
        })
        this.eventSource.onopen = () => {
          this.fetchAnnouncements()
        }
      } catch (error) {
        // EventSource not available or connection failed; polling-free live update disabled.
      }
    },
    // Display logic
    alertType (type) {
      const normalized = type === 'danger' ? 'error' : type
      return ['info', 'success', 'warning', 'error'].includes(normalized) ? normalized : 'default'
    },
    borderColor (type) {
      const colorMap = {
        error: '#ffa39e',
        warning: '#ffe58f',
        success: '#b7eb8f',
        info: '#b3cde3'
      }
      const color = colorMap[this.alertType(type)]
      return color ? `1px solid ${color}` : '0px'
    },
    isWithinDisplayPeriod (announcement) {
      if (announcement.startDate && this.now < new Date(announcement.startDate)) {
        return false
      }
      if (announcement.endDate && this.now > new Date(announcement.endDate)) {
        return false
      }
      return true
    },
    // Dismissal handling
    isDismissed (announcement) {
      const key = this.dismissedKey(announcement)
      if (this.sessionDismissed.includes(key)) {
        return true
      }
      if (!announcement.persistDismissal) {
        return false
      }
      return this.$localStorage.get(key) === 'true'
    },
    handleClose (announcement) {
      const key = this.dismissedKey(announcement)
      this.sessionDismissed.push(key)
      if (announcement.persistDismissal) {
        this.$localStorage.set(key, 'true')
      }
    },
    dismissedKey (announcement) {
      return `cs-ann-dismissed-${announcement.id}-${this.getHash(announcement.message)}`
    },
    sanitize (message) {
      return DOMPurify.sanitize(message, {
        ALLOWED_TAGS: [
          'p', 'div', 'span', 'br', 'strong', 'b', 'em', 'i', 'u',
          'a', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
          'small', 'mark', 'del', 'ins', 'sub', 'sup'
        ],
        ALLOWED_ATTR: ['href', 'target', 'rel', 'class', 'id', 'style'],
        ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|cid|xmpp|xxx):|[^a-z]|[a-z+.-]+(?:[^a-z+.\-:]|$))/i,
        FORBID_TAGS: ['script', 'object', 'embed', 'form', 'input', 'textarea', 'select', 'button'],
        FORBID_ATTR: ['onclick', 'onload', 'onerror', 'onmouseover', 'onfocus', 'onblur']
      })
    },
    getHash (str) {
      let hash = 0
      const value = str || ''
      for (let i = 0; i < value.length; i++) {
        const char = value.charCodeAt(i)
        hash = ((hash << 5) - hash) + char
        hash = hash & hash
      }
      return Math.abs(hash).toString()
    }
  }
}
</script>

<style scoped>
.announcement-banner-container {
  z-index: 1000;
  top: 0;
  margin: 0;
  width: 100%;
  justify-content: center;
  align-items: center;
}

.banner-content {
  line-height: 1.7;
  text-align: center
}
</style>
