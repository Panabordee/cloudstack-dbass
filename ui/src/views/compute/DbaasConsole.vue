<!-- DBaaS console: a Tables tab (list, describe, preview) and a SQL tab
     (read-only by default, write opt-in). Both drive the same job pipeline:
     submit a command, poll getDbaasJobResult until the state leaves pending,
     render the result once it arrives. Results are delivered exactly once per
     fetch -- this component owns the single fetch. -->
<template>
  <a-spin :spinning="loading || submitting">
    <a-tabs v-model:activeKey="activeTab" destroyInactiveTabPane>
      <a-tab-pane key="tables" :tab="$t('label.dbaas.console.tables.tab')">
        <div class="console-toolbar">
          <a-button :loading="submitting" @click="listTables">
            {{ $t('label.dbaas.console.refresh') }}
          </a-button>
        </div>
        <a-table
          v-if="tables.length > 0"
          :columns="tableListColumns"
          :data-source="tables"
          :row-key="record => record.name"
          size="small"
          :pagination="{ pageSize: 20 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'actions'">
              <a-button size="small" style="margin-right: 6px"
                @click="describeTable(record.name)">
                {{ $t('label.dbaas.console.describe') }}
              </a-button>
              <a-button size="small" @click="previewTable(record.name)">
                {{ $t('label.dbaas.console.preview') }}
              </a-button>
            </template>
          </template>
        </a-table>
        <a-empty v-else-if="!submitting && tablesFetched" :description="$t('label.dbaas.console.no.tables')" />
        <a-card v-if="describedTable" size="small" class="console-card"
          :title="$t('label.dbaas.console.describe') + ': ' + describedTable.name">
          <a-table :columns="describeColumns" :data-source="describedTable.columns"
            :row-key="record => record.name" size="small" :pagination="false" />
          <a-table v-if="describedTable.indexes && describedTable.indexes.length > 0"
            :columns="describeColumns" :data-source="describedTable.indexes"
            :row-key="record => record.name" size="small" :pagination="false" class="console-card" />
        </a-card>
      </a-tab-pane>
      <a-tab-pane key="sql" :tab="$t('label.dbaas.console.sql.tab')">
        <a-form layout="vertical">
          <a-form-item :label="$t('label.dbaas.console.sql.editor')">
            <a-textarea v-model:value="sqlText" rows="6" />
          </a-form-item>
          <a-form-item>
            <a-checkbox v-model:checked="writeMode">
              {{ $t('label.dbaas.console.write.mode') }}
            </a-checkbox>
          </a-form-item>
          <a-button type="primary" :loading="submitting" @click="runQuery">
            {{ $t('label.dbaas.console.run') }}
          </a-button>
        </a-form>
        <a-alert v-if="jobError" type="error" show-icon :message="jobError" class="console-note" />
        <a-alert v-if="truncated" type="warning" show-icon
          :message="$t('label.dbaas.console.truncated')" class="console-note" />
        <a-table v-if="resultRows.length > 0"
          :columns="resultColumns" :data-source="resultRows"
          :row-key="(record, index) => String(index)" size="small"
          :pagination="{ pageSize: 50 }" :scroll="{ x: true }" class="console-card" />
        <a-alert v-else-if="resultShown" type="success" show-icon
          :message="$t('label.dbaas.console.sql.no.rows')" class="console-note" />
      </a-tab-pane>
    </a-tabs>
  </a-spin>
</template>

<script>
import { getAPI, postAPI } from '@/api'

export default {
  name: 'DbaasConsole',
  props: {
    resource: { type: Object, required: true }
  },
  data () {
    return {
      loading: false,
      submitting: false,
      activeTab: 'tables',
      tables: [],
      tablesFetched: false,
      describedTable: null,
      sqlText: '',
      writeMode: false,
      jobError: '',
      truncated: false,
      resultColumns: [],
      resultRows: [],
      resultShown: false,
      tableListColumns: [
        { title: this.$t('label.name'), dataIndex: 'name', key: 'name' },
        { title: this.$t('label.actions'), key: 'actions' }
      ],
      describeColumns: [
        { title: this.$t('label.name'), dataIndex: 'name', key: 'name' },
        { title: this.$t('label.type'), dataIndex: 'type', key: 'type' },
        { title: this.$t('label.nullable'), dataIndex: 'nullable', key: 'nullable' },
        { title: this.$t('label.key'), dataIndex: 'key', key: 'key' }
      ]
    }
  },
  created () {
    this.listTables()
  },
  methods: {
    // Submits a console job and polls getDbaasJobResult until the state
    // leaves pending/dispatched. The result is consumed by this single fetch.
    submitJob (command, params) {
      this.submitting = true
      this.jobError = ''
      this.truncated = false
      return postAPI(command, { virtualmachineid: this.resource.id, ...params }).then(json => {
        const body = json[command.toLowerCase() + 'response'] || {}
        const jobId = body.jobid
        if (!jobId) {
          throw new Error(this.$t('message.dbaas.console.no.job'))
        }
        return this.pollResult(jobId, 0)
      }).finally(() => {
        this.submitting = false
      })
    },
    pollResult (jobId, attempt) {
      const maxAttempts = 30
      return getAPI('getDbaasJobResult', { jobid: jobId }).then(json => {
        const body = json['getdbaasjobresultresponse'] || {}
        const state = body.state || 'pending'
        if (state === 'pending' || state === 'dispatched') {
          if (attempt >= maxAttempts) {
            throw new Error(this.$t('message.dbaas.console.job.timeout'))
          }
          return new Promise(resolve => setTimeout(resolve, 2000))
            .then(() => this.pollResult(jobId, attempt + 1))
        }
        if (state === 'expired') {
          throw new Error(this.$t('message.dbaas.console.job.expired'))
        }
        if (body.collected) {
          throw new Error(this.$t('message.dbaas.console.job.collected'))
        }
        return body
      })
    },
    parseResult (body) {
      this.truncated = body.truncated === true
      if (!body.result) {
        this.resultColumns = []
        this.resultRows = []
        this.resultShown = false
        return
      }
      const payload = JSON.parse(body.result)
      const columns = (payload.columns || []).map(name => ({ title: name, dataIndex: name, key: name }))
      const rows = (payload.rows || []).map(row => {
        const entry = {}
        columns.forEach((column, index) => { entry[column.dataIndex] = row[index] })
        return entry
      })
      this.resultColumns = columns
      this.resultRows = rows
      this.resultShown = true
    },
    listTables () {
      this.describedTable = null
      this.resultShown = false
      this.submitJob('listDbaasTables', {}).then(body => {
        const payload = JSON.parse(body.result || '{}')
        this.tables = (payload.tables || []).map(name => ({ name }))
        this.tablesFetched = true
      }).catch(error => this.fail(error))
    },
    describeTable (name) {
      this.submitJob('describeDbaasTable', { table: name }).then(body => {
        const payload = JSON.parse(body.result || '{}')
        this.describedTable = {
          name: name,
          columns: payload.columns || [],
          indexes: payload.indexes || []
        }
      }).catch(error => this.fail(error))
    },
    previewTable (name) {
      this.submitJob('previewDbaasTable', { table: name, limit: 100, offset: 0 }).then(body => {
        this.describedTable = null
        this.parseResult(body)
        this.activeTab = 'sql'
      }).catch(error => this.fail(error))
    },
    runQuery () {
      this.describedTable = null
      this.resultShown = false
      this.submitJob('runDbaasQuery', { sql: this.sqlText, write: this.writeMode }).then(body => {
        this.parseResult(body)
      }).catch(error => this.fail(error))
    },
    fail (error) {
      this.jobError = error.message || String(error)
    }
  }
}
</script>

<style scoped>
.console-note {
  margin: 8px 0;
}
.console-card {
  margin-top: 12px;
}
</style>
