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
  <div class="form" v-ctrl-enter="submitData">

    <div v-if="loading" class="loading">
      <loading-outlined style="color: #1890ff;" />
    </div>

    <a-alert type="info" style="margin-bottom: 20px">
      <template #message>
        <label v-html="$t('message.add.instance.proxy')"></label>
      </template>
    </a-alert>

    <div class="form__item">
      <p class="form__label">{{ $t('label.proxy.domain') }}</p>
      <a-select
        v-model:value="proxyDomainId"
        :options="proxyDomainOptions"
        :loading="loadingDomains"
        style="width: 100%"
        @change="handleDomainChange"
        :placeholder="$t('label.proxy.domain')" />
      <div class="form__hint" v-if="proxyDomains.length === 0 && !loadingDomains">
        {{ $t('message.proxy.domain.none.available') }}
      </div>
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.proxy.name') }}</p>
      <a-input
        v-model:value="name"
        :placeholder="$t('label.proxy.name.placeholder')"
        @input="handleNameInput">
        <template #addonAfter>
          <span v-if="proxyDomain" class="proxy-fqdn-suffix">.{{ proxyDomain }}</span>
          <a-tooltip :title="$t('label.proxy.random')">
            <a-button type="text" size="small" class="proxy-random-btn" @click="randomizeName">
              <template #icon><sync-outlined /></template>
            </a-button>
          </a-tooltip>
        </template>
      </a-input>
      <div class="form__hint" v-if="checkingName">
        {{ $t('label.proxy.checking') }}
      </div>
      <a-alert
        v-else-if="nameChecked && !nameAvailable"
        type="error"
        style="margin-top: 5px">
        <template #message>{{ nameCheckMessage }}</template>
      </a-alert>
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.proxy.protocol') }}</p>
      <a-radio-group v-model:value="protocol">
        <a-radio value="http">http</a-radio>
        <a-radio value="https">https</a-radio>
      </a-radio-group>
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.proxy.port') }}</p>
      <a-input-number
        v-model:value="port"
        :min="1"
        :max="65535"
        style="width: 100%" />
    </div>

    <div class="submit-btn">
      <a-button @click="closeAction">
        {{ $t('label.cancel') }}
      </a-button>
      <a-button type="primary" @click="submitData" ref="submit" :disabled="checkingName || (nameChecked && !nameAvailable) || !proxyDomainId">
        {{ $t('label.ok') }}
      </a-button>
    </div>

  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'

const RANDOM_ADJECTIVES = ['swift', 'bright', 'calm', 'brave', 'clever', 'eager', 'gentle', 'happy',
  'jolly', 'kind', 'lively', 'mellow', 'noble', 'quiet', 'rapid', 'sunny', 'tidy', 'vivid', 'wise', 'bold']
const RANDOM_NOUNS = ['falcon', 'tiger', 'panda', 'otter', 'lynx', 'maple', 'willow', 'cedar', 'quartz',
  'zephyr', 'harbor', 'meadow', 'summit', 'river', 'forest', 'ember', 'comet', 'orbit', 'delta', 'coral']

export default {
  name: 'AddInstanceProxy',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      name: '',
      protocol: 'http',
      port: 80,
      proxyDomains: [],
      proxyDomainId: undefined,
      proxyDomain: '',
      loadingDomains: false,
      checkingName: false,
      nameChecked: false,
      nameAvailable: false,
      nameCheckMessage: '',
      checkTimer: null,
      loading: false,
      firstActivated: true
    }
  },
  computed: {
    proxyDomainOptions () {
      return this.proxyDomains.map(domain => {
        return { label: domain.domain, value: domain.id }
      })
    }
  },
  mounted () {
    this.fetchProxyDomains()
  },
  activated () {
    if (this.firstActivated) {
      // The initial activation, mounted() has already fetched the proxy domains
      this.firstActivated = false
      return
    }
    // The action modal keeps this component alive (keep-alive), reset the form
    // so that a stale name or availability check is not shown when reopened
    this.resetForm()
    this.fetchProxyDomains()
  },
  beforeUnmount () {
    if (this.checkTimer) {
      clearTimeout(this.checkTimer)
    }
  },
  methods: {
    resetForm () {
      if (this.checkTimer) {
        clearTimeout(this.checkTimer)
      }
      this.checkTimer = null
      this.name = ''
      this.protocol = 'http'
      this.port = 80
      this.checkingName = false
      this.nameChecked = false
      this.nameAvailable = false
      this.nameCheckMessage = ''
      this.loading = false
    },
    fetchProxyDomains () {
      if (!('listReverseProxyDomains' in this.$store.getters.apis)) {
        return
      }
      this.loadingDomains = true
      getAPI('listReverseProxyDomains', {
        virtualmachineid: this.resource.id
      }).then(response => {
        this.proxyDomains = response.listreverseproxydomainsresponse.reverseproxydomain || []
        if (this.proxyDomains.length > 0) {
          this.proxyDomainId = this.proxyDomains[0].id
          this.proxyDomain = this.proxyDomains[0].domain
        } else {
          this.proxyDomainId = undefined
          this.proxyDomain = ''
        }
      }).catch(() => {}).finally(() => {
        this.loadingDomains = false
      })
    },
    handleDomainChange () {
      const selected = this.proxyDomains.find(domain => domain.id === this.proxyDomainId)
      this.proxyDomain = selected ? selected.domain : ''
      this.nameChecked = false
      this.nameAvailable = false
      this.nameCheckMessage = ''
      if (this.name && this.name.trim()) {
        this.handleNameInput()
      }
    },
    randomizeName () {
      const pick = (arr) => arr[Math.floor(Math.random() * arr.length)]
      this.name = pick(RANDOM_ADJECTIVES) + '-' + pick(RANDOM_NOUNS) + '-' + Math.floor(100 + Math.random() * 900)
      this.handleNameInput()
    },
    handleNameInput () {
      this.nameChecked = false
      this.nameAvailable = false
      this.nameCheckMessage = ''
      if (this.checkTimer) {
        clearTimeout(this.checkTimer)
      }
      if (!this.name || !this.name.trim()) {
        return
      }
      if (!('checkInstanceProxyName' in this.$store.getters.apis)) {
        return
      }
      this.checkTimer = setTimeout(() => {
        this.checkName()
      }, 600)
    },
    checkName () {
      this.checkingName = true
      postAPI('checkInstanceProxyName', {
        name: this.name.trim(),
        domainid: this.proxyDomainId
      }).then(response => {
        const result = response.checkinstanceproxynameresponse?.instanceproxyname || {}
        this.nameChecked = true
        this.nameAvailable = !!result.available
        this.nameCheckMessage = result.message || ''
      }).catch(error => {
        this.nameChecked = false
        this.$notifyError(error)
      }).finally(() => {
        this.checkingName = false
      })
    },
    closeAction () {
      this.$emit('close-action')
    },
    submitData () {
      if (this.loading) return
      if (!this.proxyDomainId) {
        this.$notification.error({
          message: this.$t('label.proxy.domain'),
          description: this.$t('message.proxy.domain.none.available')
        })
        return
      }
      if (!this.name || !this.name.trim()) {
        this.$notification.error({
          message: this.$t('label.proxy.name'),
          description: this.$t('message.proxy.name.required')
        })
        return
      }
      if (!this.port || this.port < 1 || this.port > 65535) {
        this.$notification.error({
          message: this.$t('label.proxy.port'),
          description: this.$t('message.proxy.port.invalid')
        })
        return
      }
      this.loading = true
      postAPI('addInstanceProxy', {
        virtualmachineid: this.resource.id,
        name: this.name.trim(),
        domainid: this.proxyDomainId,
        protocol: this.protocol,
        port: this.port
      }).then(response => {
        this.$notification.success({
          message: this.$t('label.reverseproxy'),
          description: this.$t('message.success.add.instance.proxy')
        })
        this.$emit('close-action')
        if (this.parentFetchData) {
          this.parentFetchData()
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style scoped lang="scss">
  .form {
    width: 85vw;

    @media (min-width: 760px) {
      width: 500px;
    }

    display: flex;
    flex-direction: column;

    &__item {
      display: flex;
      flex-direction: column;
      width: 100%;
      margin-bottom: 10px;
    }

    &__label {
      display: flex;
      font-weight: bold;
      margin-bottom: 5px;
    }

  &__hint {
    margin-top: 5px;
    color: rgba(0, 0, 0, 0.45);
  }

  .proxy-fqdn-suffix {
    margin-right: 4px;
    white-space: nowrap;
    user-select: all;
  }

  .proxy-random-btn {
    padding: 0 4px;
    height: auto;
  }
  }

  .submit-btn {
    margin-top: 10px;
    align-self: flex-end;

    button {
      margin-left: 10px;
    }
  }

  .loading {
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 3rem;
  }
</style>
