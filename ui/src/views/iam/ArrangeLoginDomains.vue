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
  <a-spin :spinning="loading">
    <div style="margin-bottom: 16px">
      {{ $t('message.arrange.login.domains') }}
    </div>
    <div class="domain-list ant-list ant-list-bordered">
      <draggable
        v-model="domains"
        item-key="id"
        handle=".drag-handle"
        animation="200"
        ghostClass="drag-ghost">
        <template #item="{ element, index }">
          <div class="domain-row ant-list-item">
            <div class="domain-row-col drag-handle">
              <drag-outlined />
            </div>
            <div class="domain-row-col domain-row-index">
              {{ index + 1 }}
            </div>
            <div class="domain-row-col domain-row-name">
              <div class="domain-row-title">{{ element.displayname || element.name }}</div>
              <div class="domain-row-path">{{ element.path }}</div>
            </div>
            <div class="domain-row-col domain-row-switch">
              <a-switch
                v-model:checked="element.showonlogin"
                :checked-children="$t('label.show.on.login')"
                :disabled="element.name === 'ROOT'"
                size="small"
              />
            </div>
          </div>
        </template>
      </draggable>
    </div>
    <div :span="24" class="action-button">
      <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" ref="submit" :loading="saving" @click="save">{{ $t('label.ok') }}</a-button>
    </div>
  </a-spin>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import draggable from 'vuedraggable'

export default {
  name: 'ArrangeLoginDomains',
  components: {
    draggable
  },
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
  inject: ['parentCloseAction', 'parentFetchData', 'parentForceRerender'],
  data () {
    return {
      loading: false,
      saving: false,
      domains: []
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    fetchData () {
      this.loading = true
      getAPI('listDomains', { listall: true, details: 'min' }).then(json => {
        const domains = json.listdomainsresponse.domain || []
        domains.sort((a, b) => {
          const keyDiff = (a.sortkey || 0) - (b.sortkey || 0)
          if (keyDiff !== 0) {
            return keyDiff
          }
          if (a.path < b.path) {
            return -1
          }
          if (a.path > b.path) {
            return 1
          }
          return 0
        })
        this.domains = domains
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    save () {
      this.saving = true
      const requests = []
      this.domains.forEach((domain, index) => {
        const params = { id: domain.id, sortkey: index }
        if (domain.name !== 'ROOT') {
          params.showonlogin = domain.showonlogin
        }
        requests.push(postAPI('updateDomain', params))
      })
      Promise.all(requests).then(() => {
        this.$message.success({
          content: this.$t(this.action.label),
          duration: 2
        })
        this.parentFetchData()
        this.closeAction()
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.saving = false
      })
    },
    closeAction () {
      this.parentCloseAction()
    }
  }
}
</script>

<style scoped>
.domain-list {
  max-height: 400px;
  overflow-y: auto;
}

.domain-row {
  cursor: default;
  display: flex;
  align-items: center;
  padding: 8px 12px;
}

.domain-row-col {
  padding: 0 8px;
}

.drag-handle {
  cursor: grab;
  color: #8c8c8c;
  font-size: 16px;
}

.domain-row-index {
  width: 28px;
  color: #8c8c8c;
}

.domain-row-name {
  flex: 1;
  min-width: 0;
}

.domain-row-title {
  font-weight: 500;
}

.domain-row-path {
  font-size: 12px;
  color: #8c8c8c;
  word-break: break-all;
}

.drag-ghost {
  opacity: 0.4;
  background: #e6f7ff;
}
</style>
