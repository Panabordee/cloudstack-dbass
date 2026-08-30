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

    <div class="form__item">
      <p class="form__label">{{ $t('label.domain') }}</p>
      <a-input
        v-model:value="domain"
        :placeholder="$t('label.proxy.domain.placeholder')"
        :disabled="isUpdate" />
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.description') }}</p>
      <a-input v-model:value="description" />
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.ispublic') }}</p>
      <a-switch v-model:checked="isPublic" />
      <div class="form__hint">{{ $t('message.proxy.domain.ispublic') }}</div>
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.npmcertificateid') }}</p>
      <a-input-number v-model:value="npmCertificateId" :min="0" style="width: 100%" />
      <div class="form__hint">{{ $t('message.proxy.domain.certificate') }}</div>
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.accounts') }}</p>
      <a-select
        v-model:value="accountIds"
        mode="multiple"
        showSearch
        optionFilterProp="label"
        :options="accountOptions"
        :loading="loadingAccounts"
        style="width: 100%"
        :placeholder="$t('label.accounts')" />
      <div class="form__hint">{{ $t('message.proxy.domain.accounts') }}</div>
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.networks') }}</p>
      <a-select
        v-model:value="networkIds"
        mode="multiple"
        showSearch
        optionFilterProp="label"
        :options="networkOptions"
        :loading="loadingNetworks"
        style="width: 100%"
        :placeholder="$t('label.networks')" />
      <div class="form__hint">{{ $t('message.proxy.domain.networks') }}</div>
    </div>

    <div class="submit-btn">
      <a-button @click="closeAction">
        {{ $t('label.cancel') }}
      </a-button>
      <a-button type="primary" @click="submitData" ref="submit">
        {{ $t('label.ok') }}
      </a-button>
    </div>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'

export default {
  name: 'ReverseProxyDomainForm',
  props: {
    resource: {
      type: Object,
      default: () => {}
    },
    action: {
      type: Object,
      default: () => {}
    }
  },
  data () {
    return {
      isUpdate: false,
      domain: '',
      description: '',
      isPublic: false,
      npmCertificateId: 0,
      accountIds: [],
      networkIds: [],
      accountOptions: [],
      networkOptions: [],
      loadingAccounts: false,
      loadingNetworks: false,
      loading: false
    }
  },
  mounted () {
    this.isUpdate = this.action?.currentAction?.api === 'updateReverseProxyDomain' && this.resource?.id
    if (this.isUpdate) {
      this.domain = this.resource.domain || ''
      this.description = this.resource.description || ''
      this.isPublic = this.resource.ispublic === true
      this.npmCertificateId = this.resource.npmcertificateid || 0
      this.accountIds = this.resource.accountids || []
      this.networkIds = this.resource.networkids || []
    }
    this.fetchAccounts()
    this.fetchNetworks()
  },
  methods: {
    fetchAccounts () {
      this.loadingAccounts = true
      getAPI('listAccounts', { listAll: true, details: 'min' }).then(response => {
        this.accountOptions = (response.listaccountsresponse.account || []).map(account => {
          return { label: account.name, value: account.id }
        })
      }).catch(() => {}).finally(() => {
        this.loadingAccounts = false
      })
    },
    fetchNetworks () {
      this.loadingNetworks = true
      getAPI('listNetworks', { listAll: true, type: 'Shared', details: 'min' }).then(response => {
        this.networkOptions = (response.listnetworksresponse.network || []).map(network => {
          return { label: network.name, value: network.id }
        })
      }).catch(() => {}).finally(() => {
        this.loadingNetworks = false
      })
    },
    closeAction () {
      this.$emit('close-action')
    },
    submitData () {
      if (this.loading) return
      if (!this.isUpdate && (!this.domain || !this.domain.trim())) {
        this.$notification.error({
          message: this.$t('label.add.proxy.domain'),
          description: this.$t('message.proxy.domain.required')
        })
        return
      }
      this.loading = true
      const params = {
        description: this.description,
        ispublic: this.isPublic,
        npmcertificateid: this.npmCertificateId || 0,
        accountids: this.accountIds.join(','),
        networkids: this.networkIds.join(',')
      }
      if (this.isUpdate) {
        params.id = this.resource.id
      } else {
        params.domain = this.domain.trim()
      }
      postAPI(this.isUpdate ? 'updateReverseProxyDomain' : 'addReverseProxyDomain', params).then(response => {
        this.$notification.success({
          message: this.$t(this.isUpdate ? 'label.edit' : 'label.add.proxy.domain'),
          description: this.$t(this.isUpdate ? 'message.success.update.proxy.domain' : 'message.success.add.proxy.domain')
        })
        this.$emit('close-action')
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
