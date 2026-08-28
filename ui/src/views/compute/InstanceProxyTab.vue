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
    <a-button
      v-if="'addInstanceProxy' in $store.getters.apis"
      type="primary"
      style="width: 100%; margin-bottom: 10px"
      @click="showAddProxyModal">
      <template #icon><global-outlined /></template> {{ $t('label.action.add.instance.proxy') }}
    </a-button>

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

    <a-modal
      :visible="showAddProxyModalVisible"
      :title="$t('label.action.add.instance.proxy')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeAddProxyModal">
      <AddInstanceProxy :resource="resource" @close-action="closeAddProxyModal" />
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { message } from 'ant-design-vue'
import TooltipButton from '@/components/widgets/TooltipButton'
import AddInstanceProxy from '@/views/compute/AddInstanceProxy.vue'

export default {
  name: 'InstanceProxyTab',
  components: {
    TooltipButton,
    AddInstanceProxy
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      proxies: [],
      loading: false,
      showAddProxyModalVisible: false,
      columns: [
        {
          key: 'fqdn',
          title: this.$t('label.proxy.fqdn'),
          dataIndex: 'fqdn'
        },
        {
          key: 'backend',
          title: this.$t('label.proxy.backend'),
          dataIndex: 'backend'
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
    }
  },
  methods: {
    fetchProxies () {
      if (!this.resource || !this.resource.id) {
        return
      }
      this.loading = true
      getAPI('listInstanceProxies', {
        virtualmachineid: this.resource.id,
        listAll: true
      }).then(response => {
        this.proxies = response.listinstanceproxiesresponse.instanceproxy || []
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
    showAddProxyModal () {
      this.showAddProxyModalVisible = true
    },
    closeAddProxyModal () {
      this.showAddProxyModalVisible = false
      this.fetchProxies()
      if (this.parentFetchData) {
        this.parentFetchData()
      }
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
