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
  <div>
    <a-table
      size="middle"
      :columns="columns"
      :dataSource="proxies"
      :loading="loading"
      :pagination="false"
      rowKey="id">
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'fqdn'">
          <a :href="record.url" target="_blank">{{ text }}</a>
          <tooltip-button
            :tooltip="$t('label.copy')"
            icon="copy-outlined"
            type="dashed"
            size="small"
            @onClick="copyFqdn(record)" />
        </template>
        <template v-else-if="column.key === 'backend'">
          {{ record.protocol }}://{{ record.ipaddress }}:{{ record.port }}
        </template>
        <template v-else-if="column.key === 'virtualmachinename'">
          <router-link v-if="record.virtualmachineid" :to="{ path: '/vm/' + record.virtualmachineid }">{{ text }}</router-link>
          <span v-else>{{ text }}</span>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-popconfirm
            v-if="'deleteInstanceProxy' in $store.getters.apis"
            :title="$t('message.remove.instance.proxy')"
            @confirm="removeProxy(record)">
            <tooltip-button
              :tooltip="$t('label.remove.instance.proxy')"
              icon="delete-outlined"
              type="primary"
              danger
              size="small" />
          </a-popconfirm>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { message } from 'ant-design-vue'
import TooltipButton from '@/components/widgets/TooltipButton'

export default {
  name: 'ReverseProxyDomainHostsTab',
  components: {
    TooltipButton
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      proxies: [],
      loading: false,
      columns: [
        {
          key: 'fqdn',
          title: this.$t('label.proxy.fqdn'),
          dataIndex: 'fqdn'
        },
        {
          key: 'virtualmachinename',
          title: this.$t('label.instances'),
          dataIndex: 'virtualmachinename'
        },
        {
          key: 'backend',
          title: this.$t('label.proxy.backend'),
          dataIndex: 'backend'
        },
        {
          key: 'account',
          title: this.$t('label.account'),
          dataIndex: 'account'
        },
        {
          key: 'state',
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          key: 'created',
          title: this.$t('label.created'),
          dataIndex: 'created'
        },
        {
          key: 'actions',
          title: this.$t('label.actions'),
          dataIndex: 'actions'
        }
      ]
    }
  },
  mounted () {
    this.fetchProxies()
  },
  watch: {
    resource (newItem, oldItem) {
      if (newItem && newItem.id !== oldItem?.id) {
        this.fetchProxies()
      }
    },
    '$route.query.tab' (newTab) {
      if (newTab === 'exposedinstances') {
        this.fetchProxies()
      }
    }
  },
  methods: {
    fetchProxies () {
      if (!this.resource || !this.resource.id) {
        return
      }
      this.loading = true
      getAPI('listReverseProxyHosts', {
        domainid: this.resource.id,
        listAll: true
      }).then(response => {
        this.proxies = response.listreverseproxyhostsresponse.instanceproxy || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    copyFqdn (record) {
      navigator.clipboard.writeText(record.url || record.fqdn).then(() => {
        message.success(this.$t('label.copied.clipboard'))
      }).catch(() => {
        message.error(this.$t('message.copy.failed'))
      })
    },
    removeProxy (record) {
      this.loading = true
      postAPI('deleteInstanceProxy', {
        id: record.id
      }).then(response => {
        this.$notification.success({
          message: this.$t('label.reverseproxy'),
          description: this.$t('message.success.remove.instance.proxy')
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
        this.fetchProxies()
      })
    }
  }
}
</script>
