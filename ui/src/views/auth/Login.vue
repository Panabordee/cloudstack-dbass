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
  <div class="login-container" :class="{ dark: darkMode }">
    <!-- Left Side - Image Only -->
    <div class="login-image-container">
      <div class="image-overlay"></div>
    </div>

    <!-- Right Side - Login Form -->
    <div class="login-form-container">
      <a-tooltip :title="darkMode ? $t('label.light.mode') : $t('label.dark.mode')">
        <a-button class="theme-toggle" type="text" @click="toggleDarkMode">
          <svg
v-if="!darkMode"
viewBox="0 0 24 24"
width="18"
height="18"
fill="currentColor"
            aria-hidden="true">
            <path d="M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"/>
          </svg>
          <svg
v-else
viewBox="0 0 24 24"
width="18"
height="18"
fill="currentColor"
            aria-hidden="true">
            <path d="M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58a.996.996 0 0 0-1.41 0a.996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37a.996.996 0 0 0-1.41 0a.996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0a.996.996 0 0 0 0-1.41l-1.06-1.06zm1.06-10.96a.996.996 0 0 0 0-1.41a.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36a.996.996 0 0 0 0-1.41a.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z"/>
          </svg>
        </a-button>
      </a-tooltip>
      <div class="login-form-wrapper">
        <div class="login-header">
          <img :src="logo" alt="Logo" class="brand-logo" />
          <!-- <p class="login-subtitle">Sign in to your account</p> -->
        </div>

        <a-form
          id="formLogin"
          class="user-layout-login"
          :ref="formRef"
          :model="form"
          :rules="rules"
          @finish="handleSubmit"
          v-ctrl-enter="handleSubmit"
        >
          <a-form-item ref="domain" name="domain">
            <div class="field-label">{{ $t('label.domain') }}</div>
            <a-auto-complete
              class="domain-select"
              v-model:value="form.domain"
              :options="domainOptions"
              :dropdownClassName="darkMode ? 'domain-dropdown-dark' : undefined"
              :filter-option="false"
              backfill
              @search="onDomainSearch"
              @select="onDomainSelect"
              @dropdown-visible-change="onDomainDropdownVisible"
            >
              <a-input
                size="large"
                :placeholder="$t('label.domain')"
              >
                <template #prefix>
                  <project-outlined />
                </template>
              </a-input>
            </a-auto-complete>
          </a-form-item>
          <a-tabs
            class="tab-center"
            :key="$i18n.locale"
            :activeKey="customActiveKey"
            size="large"
            :tabBarStyle="{ textAlign: 'center', borderBottom: 'unset' }"
            @change="handleTabClick"
            :animated="false"
          >
            <a-tab-pane key="cs">
              <template #tab>
                <span>
                  <safety-outlined />
                  {{ $t('label.login.portal') }}
                </span>
              </template>
              <a-form-item v-if="$config.multipleServer" name="server" ref="server">
                <a-select
                  size="large"
                  :placeholder="$t('server')"
                  v-model:value="form.server"
                  @change="onChangeServer"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }">
                  <a-select-option v-for="item in $config.servers" :key="(item.apiHost || '') + item.apiBase" :label="item.name">
                    <template #prefix>
                      <database-outlined />
                    </template>
                    {{ item.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item ref="username" name="username">
                <a-input
                  size="large"
                  type="text"
                  v-focus="true"
                  :placeholder="$t('label.username')"
                  v-model:value="form.username"
                >
                  <template #prefix>
                    <user-outlined />
                  </template>
                </a-input>
              </a-form-item>
              <a-form-item ref="password" name="password">
                <a-input-password
                  size="large"
                  type="password"
                  autocomplete="false"
                  :placeholder="$t('label.password')"
                  v-model:value="form.password"
                >
                  <template #prefix>
                    <lock-outlined />
                  </template>
                </a-input-password>
              </a-form-item>
              <a-form-item ref="project" name="project" v-if="$config.displayProjectFieldOnLogin">
                <a-input
                  size="large"
                  type="text"
                  :placeholder="$t('label.project')"
                  v-model:value="form.project"
                >
                  <template #prefix>
                    <block-outlined />
                  </template>
                </a-input>
              </a-form-item>
            </a-tab-pane>
            <a-tab-pane key="saml" :disabled="idps.length === 0">
              <template #tab>
                <span>
                  <audit-outlined />
                  {{ $t('label.login.single.signon') }}
                </span>
              </template>
              <a-form-item v-if="$config.multipleServer" name="server" ref="server">
                <a-select
                  size="large"
                  :placeholder="$t('server')"
                  v-model:value="form.server"
                  @change="onChangeServer"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }" >
                  <a-select-option v-for="item in $config.servers" :key="(item.apiHost || '') + item.apiBase" :label="item.name">
                    <template #prefix>
                      <database-outlined />
                    </template>
                    {{ item.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item name="idp" ref="idp">
                <a-select
                  v-model:value="form.idp"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }" >
                  <a-select-option v-for="(idp, idx) in idps" :key="idx" :value="idp.id" :label="idp.orgName">
                    {{ idp.orgName }}
                  </a-select-option>
                </a-select>
              </a-form-item>
            </a-tab-pane>
          </a-tabs>

          <a-form-item>
            <a-button
              size="large"
              type="primary"
              html-type="submit"
              class="login-button"
              :loading="state.loginBtn"
              :disabled="state.loginBtn"
              ref="submit"
              @click="handleSubmit"
            >{{ $t('label.login') }}</a-button>
          </a-form-item>
          <a-row justify="space-between">
            <!-- <a-col>
            <translation-menu/>
            </a-col> -->
            <a-col v-if="forgotPasswordEnabled">
              <router-link :to="{ name: 'forgotPassword' }" class="forgot-password-link">
                {{ $t('label.forgot.password') }}
              </router-link>
            </a-col>
          </a-row>
          <div class="content" v-if="socialLogin && oauthButtons.length > 0">
            <p class="or">{{ $t('label.or.sign.in.with') }}</p>
          </div>
          <div v-if="socialLogin && oauthButtons.length > 0" class="oauth-section">
            <a-button
              v-for="(btn, idx) in oauthButtons"
              :key="btn.key"
              size="large"
              block
              :type="idx === 0 ? 'primary' : 'default'"
              class="oauth-login-button"
              :href="btn.url"
              @click="btn.click()"
            >
              <img v-if="btn.img" :src="btn.img" class="oauth-btn-logo" />
              <span>{{ btn.label }}</span>
            </a-button>
          </div>
        </a-form>

        <div class="login-footer" v-if="loginFooter" v-html="loginFooter"></div>
        <div class="login-footer" v-else v-html="naclFooter"></div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import store from '@/store'
import { mapActions } from 'vuex'
import { sourceToken } from '@/utils/request'
import { SERVER_MANAGER, LAST_SELECTED_DOMAIN, LOGIN_THEME } from '@/store/mutation-types'
import { setStore, getStore } from '@/utils/storage'
import TranslationMenu from '@/components/header/TranslationMenu'

export default {
  components: {
    TranslationMenu
  },
  data () {
    return {
      idps: [],
      darkMode: false,
      customActiveKey: 'cs',
      customActiveKeyOauth: false,
      loginBtn: false,
      email: '',
      secretcode: '',
      oauthexclude: '',
      naclFooter: 'Built with 💙 by <a href="https://www.ce-nacl.com" target="_blank" rel="noopener">Network and Cloud Laboratory (NaCl)</a><br>On top of Apache CloudStack',
      googleprovider: false,
      githubprovider: false,
      keycloakprovider: false,
      keycloakname: '',
      googlelogo: '',
      githublogo: '',
      keycloaklogo: '',
      googleredirecturi: '',
      githubredirecturi: '',
      keycloakredirecturi: '',
      googleclientid: '',
      githubclientid: '',
      keycloakclientid: '',
      keycloakauthorizeurl: '',
      oauthGoogleProvider: false,
      oauthGithubProvider: false,
      oauthKeycloakProvider: false,
      oauthGoogleClientId: '',
      oauthGithubClientId: '',
      oauthKeycloakClientId: '',
      oauthGoogleRedirectUri: '',
      oauthGithubRedirectUri: '',
      oauthKeycloakRedirectUri: '',
      oauthKeycloakAuthorizeUrl: '',
      oauthGoogleLogo: '',
      oauthGithubLogo: '',
      oauthKeycloakLogo: '',
      oauthKeycloakName: '',
      oauthLoading: false,
      loginType: 0,
      state: {
        time: 60,
        loginBtn: false,
        loginType: 0
      },
      server: '',
      forgotPasswordEnabled: false,
      project: null,
      loginDomains: [],
      domainKeyword: ''
    }
  },
  computed: {
    logo () {
      return this.$config.logo || 'assets/logo.svg'
    },
    appTitle () {
      return this.$config.loginTitle || this.$config.appTitle || 'CloudStack'
    },
    loginFooter () {
      return this.$config.loginFooter || ''
    },
    socialLogin () {
      return this.oauthGoogleProvider || this.oauthGithubProvider || this.oauthKeycloakProvider
    },
    domainOptions () {
      const all = this.loginDomains.map(d => {
        const label = d.displayname || d.name
        return { value: label, label: label }
      })
      const keyword = (this.domainKeyword || '').toLowerCase()
      if (!keyword) {
        return all
      }
      return all.filter(option => option.label.toLowerCase().indexOf(keyword) >= 0)
    },
    oauthButtons () {
      const buttons = []
      if (this.oauthKeycloakProvider) {
        buttons.push({
          key: 'keycloak',
          label: this.$t('label.login.with.name', { name: this.oauthKeycloakName || this.keycloakname || 'Keycloak' }),
          img: this.oauthKeycloakLogo || this.keycloaklogo || '',
          url: this.getKeycloakUrl(this.from),
          click: this.handleKeycloakProviderAndDomain
        })
      }
      if (this.oauthGoogleProvider) {
        buttons.push({
          key: 'google',
          label: this.$t('label.login.with.name', { name: 'Google' }),
          img: this.oauthGoogleLogo || this.googlelogo || '/assets/google.svg',
          url: this.getGoogleUrl(),
          click: this.handleGoogleProviderAndDomain
        })
      }
      if (this.oauthGithubProvider) {
        buttons.push({
          key: 'github',
          label: this.$t('label.login.with.name', { name: 'GitHub' }),
          img: this.oauthGithubLogo || this.githublogo || '/assets/github.svg',
          url: this.getGitHubUrl(this.from),
          click: this.handleGithubProviderAndDomain
        })
      }
      return buttons
    }
  },
  watch: {
    'form.username' (val) {
      this.applyLastSelectedDomain(val)
    }
  },
  created () {
    this.initTheme()
    if (this.$config.multipleServer) {
      this.server = this.$localStorage.get(SERVER_MANAGER) || this.$config.servers[0]
    }
    this.initForm()
    if (store.getters.logoutFlag) {
      if (store.getters.readyForShutdownPollingJob !== '' || store.getters.readyForShutdownPollingJob !== undefined) {
        clearInterval(store.getters.readyForShutdownPollingJob)
      }
      sourceToken.init()
      this.fetchData()
    } else {
      this.fetchData()
    }
  },
  methods: {
    ...mapActions(['Login', 'Logout', 'OauthLogin']),
    initTheme () {
      const stored = this.$localStorage.get(LOGIN_THEME)
      if (stored === 'dark' || stored === 'light') {
        this.darkMode = stored === 'dark'
        return
      }
      // no explicit choice yet, follow the browser setting
      const query = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)')
      if (!query) {
        return
      }
      this.darkMode = query.matches
      const handler = (e) => {
        if (!this.$localStorage.get(LOGIN_THEME)) {
          this.darkMode = e.matches
        }
      }
      if (query.addEventListener) {
        query.addEventListener('change', handler)
      }
    },
    toggleDarkMode () {
      this.darkMode = !this.darkMode
      this.$localStorage.set(LOGIN_THEME, this.darkMode ? 'dark' : 'light')
    },
    initForm () {
      this.formRef = ref()
      const savedDomain = getStore(LAST_SELECTED_DOMAIN)
      this.form = reactive({
        server: (this.server.apiHost || '') + this.server.apiBase,
        username: this.$route.query?.username || '',
        domain: this.$route.query?.domain || savedDomain || '',
        project: null
      })
      this.rules = reactive({})
      this.setRules()
      this.applyLastSelectedDomain(this.form.username)
    },
    applyLastSelectedDomain (username) {
      if (!username || this.$route.query?.domain) {
        return
      }
      const savedDomain = getStore(LAST_SELECTED_DOMAIN + '_' + username) || getStore(LAST_SELECTED_DOMAIN)
      if (savedDomain) {
        this.form.domain = savedDomain
      }
    },
    onDomainSearch (text) {
      this.domainKeyword = text || ''
    },
    onDomainSelect () {
      this.domainKeyword = ''
      this.fetchOauthProviders(this.resolveDomain(this.form.domain))
    },
    onDomainDropdownVisible (open) {
      if (open) {
        // always show the full list of domains when the dropdown opens
        this.domainKeyword = ''
      } else {
        this.fetchOauthProviders(this.resolveDomain(this.form.domain))
      }
    },
    preselectFirstDomain () {
      if (!this.form.domain && this.loginDomains.length > 0) {
        const first = this.loginDomains[0]
        this.form.domain = first.displayname || first.name
      }
    },
    resolveDomain (domain) {
      if (!domain) {
        return domain
      }
      const match = this.loginDomains.find(d => (d.displayname || d.name) === domain)
      return match ? match.path : domain
    },
    setRules () {
      if (this.customActiveKey === 'cs' && this.customActiveKeyOauth === false) {
        this.rules.username = [
          {
            required: true,
            message: this.$t('message.error.username'),
            trigger: 'change'
          },
          {
            validator: this.handleUsernameOrEmail,
            trigger: 'change'
          }
        ]
        this.rules.password = [
          {
            required: true,
            message: this.$t('message.error.password'),
            trigger: 'change'
          }
        ]
      } else {
        this.rules.username = []
        this.rules.password = []
      }
    },
    fetchData () {
      getAPI('listLoginDomains').then(response => {
        if (response) {
          this.loginDomains = response.listlogindomainsresponse.logindomain || []
          this.preselectFirstDomain()
        }
        this.fetchOauthProviders(this.resolveDomain(this.form.domain))
      }).catch(() => {
        this.loginDomains = []
        this.fetchOauthProviders()
      })
      getAPI('listIdps').then(response => {
        if (response) {
          this.idps = response.listidpsresponse.idp || []
          this.idps.sort(function (a, b) {
            if (a.orgName < b.orgName) { return -1 }
            if (a.orgName > b.orgName) { return 1 }
            return 0
          })
          this.form.idp = this.idps[0].id || ''
        }
      })
      postAPI('forgotPassword', {}).then(response => {
        this.forgotPasswordEnabled = response.forgotpasswordresponse.enabled
      }).catch((err) => {
        if (err?.response?.data === null) {
          this.forgotPasswordEnabled = true
        } else {
          this.forgotPasswordEnabled = false
        }
      })
    },
    fetchOauthProviders (domain) {
      const params = {}
      if (domain) {
        params.domain = domain
        this.oauthLoading = true
      }
      getAPI('listOauthProvider', params).then(response => {
        if (response) {
          const oauthproviders = response.listoauthproviderresponse.oauthprovider || []
          if (!domain) {
            oauthproviders.forEach(item => {
              if (item.provider === 'google') {
                this.googleprovider = item.enabled
                this.googleclientid = item.clientid
                this.googleredirecturi = item.redirecturi
                this.googlelogo = item.logo || ''
              }
              if (item.provider === 'github') {
                this.githubprovider = item.enabled
                this.githubclientid = item.clientid
                this.githubredirecturi = item.redirecturi
                this.githublogo = item.logo || ''
              }
              if (item.provider === 'keycloak') {
                this.keycloakprovider = item.enabled
                this.keycloakclientid = item.clientid
                this.keycloakredirecturi = item.redirecturi
                this.keycloakauthorizeurl = item.authorizeurl
                this.keycloakname = item.description || 'Keycloak'
                this.keycloaklogo = item.logo || ''
              }
            })
            this.oauthGithubProvider = this.githubprovider
            this.oauthGoogleProvider = this.googleprovider
            this.oauthKeycloakProvider = this.keycloakprovider
            this.oauthGithubClientId = this.githubclientid
            this.oauthGoogleClientId = this.googleclientid
            this.oauthKeycloakClientId = this.keycloakclientid
            this.oauthGithubRedirectUri = this.githubredirecturi
            this.oauthGoogleRedirectUri = this.googleredirecturi
            this.oauthKeycloakRedirectUri = this.keycloakredirecturi
            this.oauthKeycloakAuthorizeUrl = this.keycloakauthorizeurl
            this.oauthGithubLogo = this.githublogo
            this.oauthGoogleLogo = this.googlelogo
            this.oauthKeycloakLogo = this.keycloaklogo
            this.oauthKeycloakName = this.keycloakname
          } else {
            this.oauthGithubProvider = false
            this.oauthGoogleProvider = false
            this.oauthKeycloakProvider = false
            this.oauthGithubLogo = ''
            this.oauthGoogleLogo = ''
            this.oauthKeycloakLogo = ''
            this.oauthKeycloakName = ''
            oauthproviders.forEach(item => {
              if (item.provider === 'google') {
                this.oauthGoogleProvider = item.enabled
                this.oauthGoogleClientId = item.clientid
                this.oauthGoogleRedirectUri = item.redirecturi
                this.oauthGoogleLogo = item.logo || ''
              }
              if (item.provider === 'github') {
                this.oauthGithubProvider = item.enabled
                this.oauthGithubClientId = item.clientid
                this.oauthGithubRedirectUri = item.redirecturi
                this.oauthGithubLogo = item.logo || ''
              }
              if (item.provider === 'keycloak') {
                this.oauthKeycloakProvider = item.enabled
                this.oauthKeycloakClientId = item.clientid
                this.oauthKeycloakRedirectUri = item.redirecturi
                this.oauthKeycloakAuthorizeUrl = item.authorizeurl
                this.oauthKeycloakName = item.description || 'Keycloak'
                this.oauthKeycloakLogo = item.logo || ''
              }
            })
          }
        }
      }).finally(() => {
        this.oauthLoading = false
      })
    },
    // handler
    async handleUsernameOrEmail (rule, value) {
      const { state } = this
      const regex = /^([a-zA-Z0-9_-])+@([a-zA-Z0-9_-])+((\.[a-zA-Z0-9_-]{2,3}){1,2})$/
      if (regex.test(value)) {
        state.loginType = 0
      } else {
        state.loginType = 1
      }
      return Promise.resolve()
    },
    handleTabClick (key) {
      this.customActiveKey = key
      this.setRules()
    },
    handleGithubProviderAndDomain () {
      this.handleDomain()
      this.$store.commit('SET_OAUTH_PROVIDER_USED_TO_LOGIN', 'github')
    },
    handleGoogleProviderAndDomain () {
      this.handleDomain()
      this.$store.commit('SET_OAUTH_PROVIDER_USED_TO_LOGIN', 'google')
    },
    handleKeycloakProviderAndDomain () {
      this.handleDomain()
      this.$store.commit('SET_OAUTH_PROVIDER_USED_TO_LOGIN', 'keycloak')
    },
    handleDomain () {
      const values = toRaw(this.form)
      const domain = values.domain
      if (!domain) {
        this.$store.commit('SET_DOMAIN_USED_TO_LOGIN', '/')
      } else {
        this.$store.commit('SET_DOMAIN_USED_TO_LOGIN', this.getLoginDomain(domain))
      }
    },
    getLoginDomain (domain) {
      const resolved = this.resolveDomain(domain)
      if (this.$config.loginBaseDomain) {
        if (resolved) {
          return this.$config.loginBaseDomain + '/' + resolved
        }
        return this.$config.loginBaseDomain
      }
      if (resolved) {
        return resolved
      }
      return '/'
    },
    getGitHubUrl (from) {
      const rootURl = 'https://github.com/login/oauth/authorize'
      const clientId = this.oauthGithubClientId || this.githubclientid
      const options = {
        client_id: clientId,
        scope: 'user:email',
        state: 'cloudstack'
      }

      const qs = new URLSearchParams(options)

      return `${rootURl}?${qs.toString()}`
    },
    getGoogleUrl (from) {
      const rootUrl = 'https://accounts.google.com/o/oauth2/v2/auth'
      const redirectUri = this.oauthGoogleRedirectUri || this.googleredirecturi
      const clientId = this.oauthGoogleClientId || this.googleclientid
      const options = {
        redirect_uri: redirectUri,
        client_id: clientId,
        access_type: 'offline',
        response_type: 'code',
        prompt: 'consent',
        scope: [
          'https://www.googleapis.com/auth/userinfo.profile',
          'https://www.googleapis.com/auth/userinfo.email'
        ].join(' '),
        state: 'cloudstack'
      }

      const qs = new URLSearchParams(options)

      return `${rootUrl}?${qs.toString()}`
    },
    getKeycloakUrl (from) {
      const rootURl = this.oauthKeycloakAuthorizeUrl || this.keycloakauthorizeurl
      const redirectUri = this.oauthKeycloakRedirectUri || this.keycloakredirecturi
      const clientId = this.oauthKeycloakClientId || this.keycloakclientid
      const options = {
        redirect_uri: redirectUri,
        client_id: clientId,
        response_type: 'code',
        scope: 'openid email',
        state: 'cloudstack'
      }

      const qs = new URLSearchParams(options)

      return `${rootURl}?${qs.toString()}`
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.state.loginBtn) return
      this.formRef.value.validate().then(() => {
        this.state.loginBtn = true

        const values = toRaw(this.form)
        if (this.$config.multipleServer) {
          this.axios.defaults.baseURL = (this.server.apiHost || '') + this.server.apiBase
          store.dispatch('SetServer', this.server)
        }
        if (this.customActiveKey === 'cs') {
          const loginParams = { ...values }
          delete loginParams.username
          loginParams[!this.state.loginType ? 'email' : 'username'] = values.username
          loginParams.password = values.password
          loginParams.domain = this.getLoginDomain(values.domain)
          this.Login(loginParams)
            .then((res) => this.loginSuccess(res))
            .catch(err => {
              this.requestFailed(err)
              this.state.loginBtn = false
            })
        } else if (this.customActiveKey === 'saml') {
          this.state.loginBtn = false
          var samlUrl = this.$config.apiBase + '?command=samlSso'
          if (values.idp) {
            samlUrl += ('&idpid=' + values.idp)
          }
          window.location.href = samlUrl
        }
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    handleSubmitOauth (provider) {
      this.customActiveKeyOauth = true
      this.setRules()
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        const loginParams = { ...values }
        delete loginParams.username
        loginParams.email = this.email
        loginParams.provider = provider
        loginParams.secretcode = this.secretcode
        loginParams.domain = this.getLoginDomain(values.domain)
        this.OauthLogin(loginParams)
          .then((res) => this.loginSuccess(res))
          .catch(err => {
            this.requestFailed(err)
            this.state.loginBtn = false
          })
      })
    },
    async loginSuccess (res) {
      this.$notification.destroy()
      this.$store.commit('SET_COUNT_NOTIFY', 0)
      // Save the selected domain to localStorage for next login (per user and globally)
      const values = toRaw(this.form)
      if (values.domain) {
        setStore(LAST_SELECTED_DOMAIN, values.domain)
        if (values.username) {
          setStore(LAST_SELECTED_DOMAIN + '_' + values.username, values.domain)
        }
      }
      if (store.getters.twoFaEnabled === true && store.getters.twoFaProvider !== '' && store.getters.twoFaProvider !== undefined) {
        this.$router.push({ path: '/verify2FA' }).catch(() => {})
      } else if (store.getters.twoFaEnabled === true && (store.getters.twoFaProvider === '' || store.getters.twoFaProvider === undefined)) {
        this.$router.push({ path: '/setup2FA' }).catch(() => {})
      } else {
        this.$store.commit('SET_LOGIN_FLAG', true)
        const values = toRaw(this.form)
        if (values.project) {
          await this.getProject(values.project)
          this.$store.dispatch('ProjectView', this.project.id)
          this.$store.dispatch('SetProject', this.project)
          this.$store.dispatch('ToggleTheme', this.project.id === undefined ? 'light' : 'dark')
        }
        this.$router.push({ path: '/dashboard' }).catch(() => {})
      }
    },
    getProject (projectName) {
      return new Promise((resolve, reject) => {
        getAPI('listProjects', {
          response: 'json',
          domainId: this.selectedDomain,
          details: 'min'
        }).then((response) => {
          const projects = response.listprojectsresponse.project
          this.project = projects.filter(project => project.name === projectName)?.[0] || null
          resolve(this.project)
        }).catch((error) => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      })
    },
    requestFailed (err) {
      if (err && err.response && err.response.data && err.response.data.loginresponse) {
        const error = err.response.data.loginresponse.errorcode + ': ' + err.response.data.loginresponse.errortext
        this.$message.error(`${this.$t('label.error')} ${error}`)
      } else if (err && err.response && err.response.data && err.response.data.oauthloginresponse) {
        const error = err.response.data.oauthloginresponse.errorcode + ': ' + err.response.data.oauthloginresponse.errortext
        this.$message.error(`${this.$t('label.error')} ${error}`)
      } else {
        this.$message.error(this.$t('message.login.failed'))
      }
    },
    onChangeServer (server) {
      const servers = this.$config.servers || []
      const serverFilter = servers.filter(ser => (ser.apiHost || '') + ser.apiBase === server)
      this.server = serverFilter[0] || {}
    }
  }
}
</script>

<style lang="less" scoped>
html, body {
  margin: 0;
  padding: 0;
}

.login-container {
  display: flex;
  min-height: 100vh;
  width: 100%;
  margin: 0;
  padding: 0;
  background: #ffffff;

  @media (max-width: 768px) {
    flex-direction: column;
  }
}

.login-image-container {
  flex: 1;
  position: relative;
  background-image: url('/assets/login-banner.png');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  min-height: 100vh;

  @media (max-width: 768px) {
    display: none;
    min-height: 200px;
    flex: none;
  }

  .image-overlay {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    // background: linear-gradient(135deg, rgba(24, 144, 255, 0.85) 0%, rgba(9, 109, 217, 0.9) 100%);
  }
}

.login-form-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: #ffffff;
  min-height: 100vh;
  position: relative;

  @media (max-width: 768px) {
    min-height: auto;
    padding: 40px 24px;
  }
}

.theme-toggle {
  position: absolute;
  top: 20px;
  right: 20px;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  font-size: 18px;
  color: #8c8c8c;

  &:hover {
    color: #1890ff;
    background: rgba(0, 0, 0, 0.04);
  }
}

.login-form-wrapper {
  width: 100%;
  max-width: 420px;
}

.login-header {
  margin-bottom: 32px;
  text-align: center;

  .brand-logo {
    max-width: 450px;
    height: 100px;
    margin: 0 auto 24px;
    display: block;

    @media (max-width: 768px) {
      max-width: 240px;
    }
  }

  .login-subtitle {
    font-size: 18px;
    color: #8c8c8c;
    margin: 0;
  }
}

.login-footer {
  margin-top: 40px;
  padding-top: 24px;
  border-top: 1px solid #f0f0f0;
  text-align: center;
  font-size: 14px;
  color: #8c8c8c;

  :deep(a) {
    color: #1890ff;
    text-decoration: none;

    &:hover {
      text-decoration: underline;
    }
  }
}

.user-layout-login {
  width: 100%;

  :deep(.ant-tabs-nav) {
    margin-bottom: 32px;
  }

  :deep(.ant-tabs-tab) {
    padding: 12px 20px;
    font-size: 15px;
    font-weight: 500;

    &.ant-tabs-tab-active {
      .ant-tabs-tab-btn {
        color: #1890ff;
      }
    }
  }

  :deep(.ant-tabs-ink-bar) {
    background: #1890ff;
    height: 3px;
  }

  :deep(.ant-form-item) {
    margin-bottom: 20px;
  }

  :deep(.ant-input-affix-wrapper) {
    padding: 10px 15px;
    border-radius: 6px;
    border: 1px solid #d9d9d9;

    &:hover {
      border-color: #40a9ff;
    }

    &:focus,
    &.ant-input-affix-wrapper-focused {
      border-color: #40a9ff;
      box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1);
    }

    .ant-input {
      border: none !important;
      box-shadow: none !important;

      &:focus {
        border: none !important;
        box-shadow: none !important;
      }
    }

    .ant-input-prefix {
      margin-right: 12px;
      color: #8c8c8c;
    }
  }

  :deep(.ant-input:not(.ant-input-affix-wrapper .ant-input)) {
    padding: 10px 15px;
    border-radius: 6px;
    border: 1px solid #d9d9d9;

    &:hover {
      border-color: #40a9ff;
    }

    &:focus {
      border-color: #40a9ff;
      box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1);
    }
  }

  :deep(.ant-select) {
    .ant-select-selector {
      padding: 6px 15px !important;
      border-radius: 6px !important;
      border: 1px solid #d9d9d9 !important;
      height: auto !important;
      min-height: 46px;
    }

    &:hover .ant-select-selector {
      border-color: #40a9ff !important;
    }

    &.ant-select-focused .ant-select-selector {
      border-color: #40a9ff !important;
      box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1) !important;
    }

    .ant-select-selection-item {
      line-height: 32px;
    }

    .ant-select-arrow {
      color: #8c8c8c;
    }
  }

  :deep(.domain-select .ant-input-affix-wrapper) {
    border: none !important;
    background: transparent !important;
    padding: 0 !important;
    width: 100% !important;
    height: auto !important;
    min-height: 0 !important;
    box-shadow: none !important;

    .ant-input {
      border: none !important;
      background: transparent !important;
      padding: 0 !important;
      height: auto !important;
      min-height: 0 !important;
      box-shadow: none !important;
    }

    .ant-input-prefix {
      margin: 0 12px 0 0 !important;
    }
  }

  button.login-button {
    margin-top: 8px;
    padding: 0 15px;
    font-size: 16px;
    font-weight: 600;
    height: 46px;
    width: 100%;
    border-radius: 6px;
    background: #1890ff;
    border: none;
    box-shadow: 0 2px 8px rgba(24, 144, 255, 0.3);
    transition: all 0.3s ease;

    &:hover:not(:disabled) {
      background: #40a9ff;
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(24, 144, 255, 0.4);
    }

    &:active:not(:disabled) {
      transform: translateY(0);
      background: #096dd9;
    }
  }

  .forgot-password-link {
    color: #1890ff;
    font-weight: 500;
    transition: color 0.3s ease;

    &:hover {
      color: #40a9ff;
      text-decoration: underline;
    }
  }

  .oauth-section {
    margin-top: 16px;
    margin-bottom: 4px;
  }

  .field-label {
    text-align: left;
    font-size: 14px;
    color: rgba(0, 0, 0, 0.65);
    margin-bottom: 8px;
  }

  .oauth-login-button {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 44px;
    margin-bottom: 10px;
    font-size: 15px;
    border-radius: 6px;

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
    }
  }

  .oauth-btn-logo {
    width: 22px;
    height: 22px;
    margin-right: 10px;
  }

  .content {
    margin: 20px auto;
    width: 100%;
  }

  .or {
    display: flex;
    align-items: center;
    gap: 12px;
    text-align: center;
    font-size: 14px;
    color: #8c8c8c;

    &::before,
    &::after {
      content: '';
      flex: 1;
      height: 1px;
      background: #d9d9d9;
    }
  }
}

.login-container.dark {
  background: #141414;

  .login-form-container {
    background: #141414;
  }

  .theme-toggle {
    color: rgba(255, 255, 255, 0.65);

    &:hover {
      color: #40a9ff;
      background: rgba(255, 255, 255, 0.08);
    }
  }

  .field-label {
    color: rgba(255, 255, 255, 0.65);
  }

  .login-footer {
    border-top-color: #303030;
    color: rgba(255, 255, 255, 0.45);

    :deep(a) {
      color: #40a9ff;

      &:hover {
        color: #69c0ff;
      }
    }
  }

  .forgot-password-link {
    color: #40a9ff;

    &:hover {
      color: #69c0ff;
    }
  }

  .or {
    color: rgba(255, 255, 255, 0.45);

    &::before,
    &::after {
      background: #424242;
    }
  }

  .user-layout-login {
    :deep(.ant-tabs-tab) {
      color: rgba(255, 255, 255, 0.65);

      &:hover {
        color: #40a9ff;
      }

      &.ant-tabs-tab-active {
        .ant-tabs-tab-btn {
          color: #1890ff;
        }
      }
    }

    :deep(.ant-input-affix-wrapper),
    :deep(.ant-input) {
      background: #1f1f1f;
      border-color: #424242;
      color: rgba(255, 255, 255, 0.85);

      &::placeholder {
        color: rgba(255, 255, 255, 0.3);
      }

      &:hover {
        border-color: #40a9ff;
      }
    }

    :deep(.ant-input-affix-wrapper) {
      .ant-input-prefix,
      .ant-input-suffix {
        color: rgba(255, 255, 255, 0.45);
      }
    }

    :deep(.ant-select) {
      .ant-select-selector {
        background: #1f1f1f !important;
        border-color: #424242 !important;
      }

      .ant-select-selection-item {
        color: rgba(255, 255, 255, 0.85);
      }

      .ant-select-arrow {
        color: rgba(255, 255, 255, 0.45);
      }
    }

    :deep(.ant-btn-default) {
      background: #1f1f1f;
      border-color: #424242;
      color: rgba(255, 255, 255, 0.85);

      &:hover,
      &:focus {
        border-color: #40a9ff;
        color: #40a9ff;
      }
    }
  }
}
</style>

<style lang="less">
// The domain autocomplete dropdown is rendered in a portal attached to <body>,
// outside the login container, so it cannot be themed by the scoped block above.
// !important is used deliberately: the app's global dark-mode theme ships its own
// dropdown hover rules with !important, and antd's own rules would otherwise win.
.ant-select-dropdown.domain-dropdown-dark {
  background-color: #1f1f1f !important;

  .ant-select-item {
    color: rgba(255, 255, 255, 0.85) !important;
  }

  .ant-select-item:hover,
  .ant-select-item-option-active:not(.ant-select-item-option-disabled) {
    background: rgba(255, 255, 255, 0.08) !important;
    color: rgba(255, 255, 255, 0.85) !important;
  }

  .ant-select-item-option-selected:not(.ant-select-item-option-disabled) {
    background: rgba(24, 144, 255, 0.15) !important;
    color: #40a9ff !important;

    &:hover {
      background: rgba(24, 144, 255, 0.25) !important;
      color: #69c0ff !important;
    }
  }

  .ant-empty-description {
    color: rgba(255, 255, 255, 0.45) !important;
  }
}
</style>
