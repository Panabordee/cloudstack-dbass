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
  <div class="user-menu">
    <span class="vpn-menu">
      <a-button type="default" class="vpn-button" href="https://public-storage.ce-nacl.com/cloud/NaCl%20OpenVPN.ovpn" target="_blank">
        <DownloadOutlined />
        <span class="vpn-label">VPN Profile</span>
      </a-button>
      <a-button type="text" size="small" class="vpn-info-button" href="https://docs.cloud.ce-nacl.com/vpn-setup/" target="_blank">
      <a-tooltip title="To enable all functionalities, VPN connection is required. Click here for setup instructions.">
        <InfoCircleOutlined :style="{ fontSize: '14px' }" />
      </a-tooltip>
      </a-button>
    </span>
    <span class="action">
      <create-menu v-if="device === 'desktop'" />
    </span>
    <external-link class="action"/>
    <!-- <translation-menu class="action"/> -->
    <header-notice class="action"/>
    <label class="user-menu-server-info action" v-if="$config.multipleServer">
      <database-outlined />
      <span class="user-menu-label-text" :title="server.name || server.apiBase || 'Local-Server'">
        {{ server.name || server.apiBase || 'Local-Server' }}
      </span>
    </label>
    <label class="user-menu-domain-info action" v-if="domainDisplayname">
      <project-outlined />
      <span class="user-menu-domain-text" :title="domainDisplayname">{{ domainDisplayname }}</span>
    </label>
    <a-dropdown>
      <span class="user-menu-dropdown action">
        <span v-if="image">
          <resource-icon :image="image" size="2x" style="margin-right: 5px; margin-top: -3px"/>
        </span>
        <a-avatar v-else-if="userInitials" class="user-menu-avatar avatar" size="small" :style="{ backgroundColor: $config.theme['@primary-color'], color: 'white' }">
          {{ userInitials }}
        </a-avatar>
        <a-avatar v-else class="user-menu-avatar avatar" size="small" :style="{ backgroundColor: $config.theme['@primary-color'], color: 'white' }">
          <template #icon><user-outlined /></template>
        </a-avatar>
        <span class="user-menu-nickname">{{ nickname() }}</span>
      </span>
      <template #overlay>
        <a-menu class="user-menu-wrapper" @click="handleClickMenu">
          <a-menu-item v-if="domainDisplayname" class="user-menu-item" key="domain" disabled>
            <ProjectOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name" :title="`${$t('label.domain')}: ${domainDisplayname}`">{{ $t('label.domain') }}: {{ domainDisplayname }}</span>
          </a-menu-item>
          <a-menu-item class="user-menu-item" key="profile">
            <UserOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.profilename') }}</span>
          </a-menu-item>
          <a-menu-item class="user-menu-item" key="limits">
            <ControlOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.limits') }}</span>
          </a-menu-item>
          <a-menu-item class="user-menu-item" key="timezone">
            <ClockCircleOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name" style="margin-right: 5px">{{ $t('label.use.local.timezone') }}</span>
            <a-switch :checked="$store.getters.usebrowsertimezone" />
          </a-menu-item>
          <a-menu-item class="user-menu-item" key="document">
            <QuestionCircleOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.help') }}</span>
          </a-menu-item>
          <a-menu-divider/>
          <a-menu-item class="user-menu-item" key="logout">
            <LogoutOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.logout') }}</span>
          </a-menu-item>
        </a-menu>
      </template>
    </a-dropdown>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import CreateMenu from './CreateMenu'
import ExternalLink from './ExternalLink'
import HeaderNotice from './HeaderNotice'
import TranslationMenu from './TranslationMenu'
import { mapActions, mapGetters } from 'vuex'
import ResourceIcon from '@/components/view/ResourceIcon'
import eventBus from '@/config/eventBus'
import { SERVER_MANAGER } from '@/store/mutation-types'
import { sourceToken } from '@/utils/request'
import { applyCustomGuiTheme } from '@/utils/guiTheme'

export default {
  name: 'UserMenu',
  components: {
    CreateMenu,
    ExternalLink,
    TranslationMenu,
    HeaderNotice,
    ResourceIcon
  },
  props: {
    device: {
      type: String,
      required: false,
      default: 'desktop'
    }
  },
  data () {
    return {
      image: '',
      userInitials: '',
      countNotify: 0
    }
  },
  created () {
    this.userInitials = (this.$store.getters.userInfo.firstname.toUpperCase().charAt(0) || '') +
      (this.$store.getters.userInfo.lastname.toUpperCase().charAt(0) || '')
    this.getIcon()
    eventBus.on('refresh-header', () => {
      this.getIcon()
    })
    this.$store.watch(
      (state, getters) => getters.countNotify,
      (newValue, oldValue) => {
        this.countNotify = newValue
      }
    )
  },
  watch: {
    image () {
      this.getIcon()
    }
  },
  computed: {
    server () {
      return this.$localStorage.get(SERVER_MANAGER) || this.$config.servers[0]
    },
    ...mapGetters(['domainDisplayname'])
  },
  methods: {
    ...mapActions(['Logout']),
    ...mapGetters(['nickname', 'avatar']),
    toggleUseBrowserTimezone () {
      this.$store.dispatch('SetUseBrowserTimezone', !this.$store.getters.usebrowsertimezone)
    },
    async getIcon () {
      await this.fetchResourceIcon(this.$store.getters.userInfo.id)
    },
    fetchResourceIcon (id) {
      return new Promise((resolve, reject) => {
        if (this.$store.getters.avatar) {
          this.image = this.$store.getters.avatar
          resolve(this.image)
        }
        getAPI('listUsers', {
          id: id,
          showicon: true
        }).then(json => {
          const response = json.listusersresponse.user || []
          if (response?.[0]) {
            this.image = response[0]?.icon?.base64image || ''
            this.$store.commit('SET_AVATAR', this.image)
            resolve(this.image)
          }
        }).catch(error => {
          reject(error)
        })
      })
    },
    handleClickMenu (item) {
      switch (item.key) {
        case 'profile':
          this.$router.push(`/accountuser/${this.$store.getters.userInfo.id}`)
          break
        case 'limits':
          this.$router.push(`/account/${this.$store.getters.userInfo.accountid}?tab=limits`)
          break
        case 'timezone':
          this.toggleUseBrowserTimezone()
          break
        case 'document':
          window.open(this.$config.docBase, '_blank')
          break
        case 'logout':
          this.handleLogout()
          break
      }
    },
    handleLogout () {
      this.Logout({}).finally(async () => {
        sourceToken.init()
        await applyCustomGuiTheme(null, null)
        this.$router.push('/user/login')
      }).catch(err => {
        this.$message.error({
          title: 'Failed to Logout',
          description: err.message
        })
      })
    },
    clearAllNotify () {
      this.$store.commit('SET_COUNT_NOTIFY', 0)
      this.$notification.destroy()
    }
  }
}
</script>

<style lang="less" scoped>
.user-menu {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: nowrap;
  max-width: 100%;
  min-width: 0;

  .action {
    flex-shrink: 0;
    min-width: 0;
  }

  &-server-info,
  &-domain-info {
    flex-shrink: 1;
    overflow: hidden;

    .anticon {
      flex-shrink: 0;
      margin-right: 5px;
    }
  }

  &-server-info {
    max-width: clamp(80px, 16vw, 200px);
  }

  &-domain-info {
    color: inherit;
    cursor: default;
    max-width: clamp(90px, 18vw, 220px);
  }

  &-label-text,
  &-domain-text {
    display: block;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &-nickname {
    display: inline-block;
    max-width: clamp(80px, 14vw, 180px);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    vertical-align: middle;
  }
}

.user-menu-wrapper {
  padding: 4px 0;
  min-width: 200px;
  max-width: ~"min(360px, 90vw)";
}

.user-menu-item {
  width: auto;
}

.user-menu-item-name {
  user-select: none;
  margin-left: 8px;
  display: inline-block;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.user-menu-item-icon i {
  min-width: 12px;
  margin-right: 8px;
}

.vpn-menu {
  display: flex;
  align-items: center;
  float: left;
  height: 100%;
  padding: 0 10px;
  cursor: default;
}

.vpn-button:hover {
  cursor: pointer !important;
}

@media (max-width: 1200px) {
  .user-menu .user-menu-domain-info {
    max-width: 160px;
  }
}

@media (max-width: 992px) {
  .user-menu .user-menu-domain-info {
    max-width: 120px;
  }

  .user-menu .user-menu-server-info {
    display: none;
  }
}

@media (max-width: 768px) {
  .user-menu .user-menu-domain-info {
    max-width: 90px;
  }

  .user-menu .user-menu-nickname {
    display: none;
  }

  .vpn-label {
    display: none;
  }
}
</style>
