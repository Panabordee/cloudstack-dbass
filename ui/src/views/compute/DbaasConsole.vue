<!-- DBaaS console: a Tables tab (list, describe, preview) and a SQL tab
     (read-only by default, write opt-in). Both drive the same job pipeline:
     submit a command, poll getDbaasJobResult until the state leaves pending,
     render the result once it arrives. Results are delivered exactly once per
     fetch -- this component owns the single fetch. -->
<template>
  <div class="database-console" :class="{ 'database-console-dark': $store.getters.darkMode }">
    <a-spin :spinning="loading || submitting">
      <!-- An instance can hold several databases (createDatabase may be called
           on it repeatedly). Every console command below runs against whichever
           one is selected here; with one database this is just a label. -->
      <div v-if="databases.length > 0" class="database-picker">
        <span class="database-picker-label">{{ $t('label.dbaas.console.database') }}</span>
        <a-select
          v-model:value="selectedDatabase"
          size="small"
          style="min-width: 220px"
          :disabled="databases.length < 2"
          @change="onDatabaseChange">
          <a-select-option v-for="d in databases" :key="d.database" :value="d.database">
            {{ d.database }}<span v-if="d.status !== 'confirmed'"> ({{ d.status }})</span>
          </a-select-option>
        </a-select>
      </div>
      <a-alert v-if="jobError" type="error" show-icon :message="jobError" class="console-note" />
      <a-tabs v-model:activeKey="activeTab" destroyInactiveTabPane>
        <a-tab-pane key="tables" :tab="isMongo ? 'Collections' : $t('label.dbaas.console.tables.tab')">
          <div class="console-toolbar">
            <a-button :loading="submitting" @click="listTables">
              {{ $t('label.dbaas.console.refresh') }}
            </a-button>
            <a-button
              v-if="!isMongo"
              type="primary"
              style="margin-left: 8px"
              :disabled="columnTypes.length === 0"
              :title="columnTypes.length === 0 ? $t('message.dbaas.console.ddl.unsupported') : ''"
              @click="openCreateTable">
              {{ $t('label.dbaas.console.create.table') }}
            </a-button>
          </div>
          <a-table
            v-if="tables.length > 0"
            :columns="tableListColumns"
            :data-source="tables"
            :row-key="record => record.name"
            size="small"
            :scroll="{ x: 'max-content' }"
            :pagination="{ pageSize: 20 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'actions'">
                <a-button
                  size="small"
                  style="margin-right: 6px"
                  @click="describeTable(record.name)">
                  {{ $t('label.dbaas.console.describe') }}
                </a-button>
                <a-button
                  size="small"
                  style="margin-right: 6px"
                  @click="previewTable(record.name)">
                  {{ $t('label.dbaas.console.preview') }}
                </a-button>
                <a-button
                  size="small"
                  style="margin-right: 6px"
                  @click="queryTable(record.name)">
                  {{ $t('label.dbaas.console.query') }}
                </a-button>
                <a-button
                  size="small"
                  danger
                  :disabled="!dropEnabled"
                  :title="dropEnabled ? '' : $t('message.dbaas.console.drop.disabled')"
                  @click="askDrop(record.name)">
                  {{ $t('label.dbaas.console.drop') }}
                </a-button>
              </template>
            </template>
          </a-table>
          <a-empty v-else-if="!submitting && tablesFetched" :description="isMongo ? 'No collections found' : $t('label.dbaas.console.no.tables')" />
          <a-card
            v-if="describedTable"
            size="small"
            class="console-card"
            :title="$t('label.dbaas.console.describe') + ': ' + describedTable.name">
            <a-table
              :columns="describeColumns"
              :data-source="describedTable.columns"
              :row-key="record => record.name"
              size="small"
              :pagination="false"
              :scroll="{ x: 'max-content' }" />
            <a-table
              v-if="describedTable.indexes && describedTable.indexes.length > 0"
              :columns="describeColumns"
              :data-source="describedTable.indexes"
              :row-key="record => record.name"
              size="small"
              :pagination="false"
              class="console-card" />
          </a-card>
        </a-tab-pane>
        <a-tab-pane key="sql" :tab="isMongo ? 'Query' : $t('label.dbaas.console.sql.tab')">
          <a-alert
            v-if="isMongo"
            type="info"
            show-icon
            :message="$t('message.dbaas.console.sql.mongo.hint')"
            class="console-note" />
          <div class="query-workspace">
            <div class="query-toolbar">
              <strong>{{ isMongo ? 'Query editor' : 'SQL editor' }}</strong>
              <div class="query-controls">
                <span class="query-shortcut">Ctrl / ⌘ + Enter</span>
                <a-button type="primary" :loading="submitting" :disabled="!sqlText.trim() || submitting" @click="runQuery">
                  <template #icon><play-circle-outlined /></template>
                  {{ $t('label.dbaas.console.run') }}
                </a-button>
              </div>
            </div>
            <a-textarea
              v-model:value="sqlText"
              :rows="sqlRows"
              class="sql-editor"
              :aria-label="$t(sqlEditorLabel)"
              :placeholder="isMongo ? '{ &quot;collection&quot;: &quot;name&quot;, &quot;op&quot;: &quot;find&quot;, &quot;filter&quot;: {}, &quot;limit&quot;: 100 }' : 'SELECT * FROM your_table LIMIT 100;'"
              spellcheck="false"
              @keydown="onQueryKeydown" />
            <div class="query-options">
              <a-checkbox v-model:checked="writeMode">
                Allow writes
              </a-checkbox>
              <a-tag :color="writeMode ? 'orange' : 'blue'">{{ writeMode ? 'Write enabled' : 'Read only' }}</a-tag>
            </div>
          </div>
          <div v-if="resultShown" class="query-results-heading">
            <strong>Results</strong>
            <span>{{ resultRows.length }} rows</span>
          </div>
          <a-alert
            v-if="truncated"
            type="warning"
            show-icon
            :message="$t('label.dbaas.console.truncated')"
            class="console-note" />
          <a-table
            v-if="resultRows.length > 0"
            :columns="resultColumns"
            :data-source="resultRows"
            :row-key="(record, index) => String(index)"
            size="small"
            :pagination="{ pageSize: 50 }"
            :scroll="{ x: 'max-content' }"
            class="console-card" />
          <a-alert
            v-else-if="resultShown"
            type="success"
            show-icon
            :message="$t('label.dbaas.console.sql.no.rows')"
            class="console-note" />
        </a-tab-pane>
      </a-tabs>

      <!-- Column types come from listDbaasEngines, which reads them from the
           config's types allowlist -- the same list createDbaasTable validates
           against server-side, so the form cannot offer a type the server will
           reject, and a config change reaches the UI without a rebuild. -->
      <a-modal
        :visible="createTableOpen"
        :title="$t('label.dbaas.console.create.table')"
        :confirm-loading="submitting"
        :ok-button-props="{ disabled: !createTableValid }"
        :ok-text="$t('label.dbaas.console.create.table')"
        width="720px"
        @ok="submitCreateTable"
        @cancel="closeCreateTable">
        <a-alert v-if="jobError" type="error" show-icon :message="jobError" class="console-note" />
        <a-form layout="vertical">
          <a-form-item :label="$t('label.name')" required>
            <a-input v-model:value="newTable.name" :placeholder="$t('label.name')" />
          </a-form-item>
        </a-form>
        <a-table
          :columns="newColumnColumns"
          :data-source="newTable.columns"
          :row-key="(record, index) => String(index)"
          size="small"
          :pagination="false">
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.key === 'name'">
              <a-input v-model:value="record.name" size="small" />
            </template>
            <template v-else-if="column.key === 'type'">
              <a-select v-model:value="record.type" size="small" style="width: 100%">
                <a-select-option v-for="t in columnTypes" :key="t" :value="t">{{ t }}</a-select-option>
              </a-select>
              <a-input-number
                v-if="/\(n\)$/i.test(record.type || '')"
                v-model:value="record.size"
                :min="1"
                :max="65535"
                :precision="0"
                aria-label="Length or precision"
                placeholder="Length or precision"
                style="width: 100%; margin-top: 4px" />
            </template>
            <template v-else-if="column.key === 'primary'">
              <a-checkbox v-model:checked="record.primary" />
            </template>
            <template v-else-if="column.key === 'nullable'">
              <a-checkbox v-model:checked="record.nullable" />
            </template>
            <template v-else-if="column.key === 'remove'">
              <a-button
                size="small"
                danger
                :disabled="newTable.columns.length <= 1"
                @click="removeColumn(index)">
                &times;
              </a-button>
            </template>
          </template>
        </a-table>
        <a-button size="small" style="margin-top: 8px" @click="addColumn">
          {{ $t('label.dbaas.console.add.column') }}
        </a-button>
        <a-alert
          v-if="!writeEnabled"
          type="info"
          show-icon
          class="console-note"
          :message="$t('message.dbaas.console.ddl.needs.owner')" />
      </a-modal>

      <!-- The API already requires `confirm` to repeat the table name exactly;
           this dialog is that requirement made visible rather than a second,
           softer one. The warning states what actually protects the tenant:
           the agent dumps the table to the instance's own disk first and
           refuses the drop outright if that dump fails. -->
      <a-modal
        :visible="dropTarget !== ''"
        :title="$t('label.dbaas.console.drop') + ': ' + dropTarget"
        :confirm-loading="submitting"
        :ok-button-props="{ danger: true, disabled: dropConfirm !== dropTarget }"
        :ok-text="$t('label.dbaas.console.drop')"
        @ok="confirmDrop"
        @cancel="cancelDrop">
        <a-alert
          type="warning"
          show-icon
          class="console-note"
          :message="$t('message.dbaas.console.drop.warning')" />
        <a-form layout="vertical">
          <a-form-item :label="$t('label.dbaas.console.drop.confirm')">
            <a-input v-model:value="dropConfirm" :placeholder="dropTarget" />
          </a-form-item>
        </a-form>
      </a-modal>
    </a-spin>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'

export default {
  name: 'DbaasConsole',
  props: {
    resource: { type: Object, required: true },
    // Set by the Database Query page, which has already asked which database
    // to open. Empty elsewhere, where the picker chooses for itself.
    initialDatabase: { type: String, default: '' },
    initialTab: { type: String, default: 'tables' }
  },
  data () {
    return {
      loading: false,
      submitting: false,
      activeTab: this.initialTab,
      tables: [],
      tablesFetched: false,
      describedTable: null,
      // Whether dbaas.console.drop.enabled is on. listConfigurations is an
      // admin API, so a tenant cannot read it -- default to enabled and let
      // the server refuse with its own message rather than hide a button
      // from someone who might be allowed to use it. For an admin the real
      // value arrives a moment later and the button greys out with a reason.
      dropEnabled: true,
      dropTarget: '',
      dropConfirm: '',
      // Fetched from listDbaasEngines for this instance's template, never
      // hardcoded: an engine with no allowlist (mongodb) offers no schema
      // DDL at all, and the Create Table button stays disabled for it.
      databases: [],
      selectedDatabase: undefined,
      columnTypes: [],
      writeEnabled: true,
      createTableOpen: false,
      newTable: { name: '', columns: [] },
      newColumnColumns: [
        { title: this.$t('label.dbaas.console.column.name'), key: 'name' },
        { title: this.$t('label.dbaas.console.column.type'), key: 'type' },
        { title: this.$t('label.dbaas.console.column.primary'), key: 'primary', width: 110 },
        { title: this.$t('label.dbaas.console.column.nullable'), key: 'nullable', width: 100 },
        { title: '', key: 'remove', width: 50 }
      ],
      sqlText: '',
      sqlRows: 5,
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
  computed: {
    // Mirrors what createDbaasTable itself will accept, so the button does
    // not invite a request the server is going to refuse: a table name, at
    // least one column, and every column named and typed.
    createTableValid () {
      const idOk = v => /^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(v || '')
      return idOk(this.newTable.name) &&
        this.newTable.columns.length > 0 &&
        this.newTable.columns.every(c => idOk(c.name) && !!this.columnType(c))
    },
    // Same detection quoteIdent already uses: the template name is the only
    // place the engine is known client-side, there is no separate field for it.
    isMongo () {
      return /mongodb/i.test(this.resource.templatename || '')
    },
    sqlEditorLabel () {
      return this.isMongo ? 'label.dbaas.console.sql.editor.mongo' : 'label.dbaas.console.sql.editor'
    }
  },
  created () {
    // Databases first: the table list has to be scoped to one of them.
    this.fetchDatabases().then(() => this.listTables())
    this.checkDropEnabled()
    this.fetchEngineTypes()
  },
  methods: {
    // Submits a console job and polls getDbaasJobResult until the state
    // leaves pending/dispatched. The result is consumed by this single fetch.
    submitJob (command, params) {
      this.submitting = true
      this.jobError = ''
      this.truncated = false
      // Sent on every command, so a job can never quietly land on a
      // different database than the one on screen. Omitted when nothing is
      // selected, which the server reads as the instance's default.
      const scoped = this.selectedDatabase
        ? { database: this.selectedDatabase, ...params }
        : { ...params }
      return postAPI(command, { virtualmachineid: this.resource.id, ...scoped }).then(json => {
        const body = (json[command.toLowerCase() + 'response'] || {}).dbaasjob || {}
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
        const body = (json.getdbaasjobresultresponse || {}).dbaasjobresult || {}
        const state = body.state || 'pending'
        if (state === 'pending' || state === 'dispatched') {
          if (attempt >= maxAttempts) {
            throw new Error(this.$t('message.dbaas.console.job.timeout'))
          }
          return new Promise(resolve => setTimeout(resolve, 2000))
            .then(() => this.pollResult(jobId, attempt + 1))
        }
        if (state === 'failed') {
          throw new Error(body.error || 'The database operation failed')
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
      // ellipsis + a width floor: a single long value (or a long column
      // name) otherwise widens its column until the table runs off the
      // panel, and horizontal scrolling alone still leaves the first
      // columns unreadable. The full value stays available on hover and
      // through the row expander below.
      const columns = (payload.columns || []).map((name, index) => ({
        title: name,
        dataIndex: 'column_' + index,
        key: 'column_' + index,
        customRender: ({ text }) => text === null ? 'NULL' : text,
        ellipsis: { showTitle: true },
        width: Math.min(320, Math.max(120, String(name).length * 9 + 32))
      }))
      const rows = (payload.rows || []).map(row => {
        const entry = {}
        columns.forEach((column, index) => { entry[column.dataIndex] = row[index] })
        return entry
      })
      this.resultColumns = columns
      this.resultRows = rows
      this.resultShown = true
    },
    fetchDatabases () {
      return getAPI('listDbaasDatabases', { virtualmachineid: this.resource.id }).then(json => {
        const list = (json.listdbaasdatabasesresponse || {}).dbaasdatabase || []
        // A row provisioned before the db_name column existed reports no
        // name; it is the instance's default and needs no entry here.
        this.databases = list.filter(d => !!d.database)
        if (this.initialDatabase &&
            this.databases.some(d => d.database === this.initialDatabase)) {
          this.selectedDatabase = this.initialDatabase
        }
        if (!this.selectedDatabase && this.databases.length > 0) {
          const confirmed = this.databases.find(d => d.status === 'confirmed')
          this.selectedDatabase = (confirmed || this.databases[0]).database
        }
      }).catch(() => {
        // An older management server has no such command: leave the picker
        // hidden and let every job use the instance's default database.
        this.databases = []
      })
    },
    onDatabaseChange () {
      // Everything on screen belongs to the previous database.
      this.describedTable = null
      this.resultRows = []
      this.resultColumns = []
      this.resultShown = false
      this.jobError = ''
      this.listTables()
    },
    fetchEngineTypes () {
      getAPI('listDbaasEngines').then(json => {
        const engines = (json.listdbaasenginesresponse || {}).dbaasengine || []
        const mine = engines.find(e => e.template === this.resource.templatename)
        this.columnTypes = (mine && mine.types) || []
      }).catch(() => {
        // Leave the list empty: the Create Table button stays disabled
        // rather than offering types that may not be accepted.
        this.columnTypes = []
      })
      getAPI('listConfigurations', { name: 'dbaas.console.write.enabled' }).then(json => {
        const cfg = (json.listconfigurationsresponse || {}).configuration || []
        if (cfg.length > 0) {
          this.writeEnabled = String(cfg[0].value) === 'true'
        }
      }).catch(() => {})
    },
    columnType (column) {
      if (!/\(n\)$/i.test(column.type || '')) {
        return column.type || ''
      }
      const size = Number(column.size)
      return Number.isInteger(size) && size >= 1 && size <= 65535
        ? column.type.replace(/\(n\)$/i, '(' + size + ')')
        : ''
    },
    openCreateTable () {
      this.newTable = {
        name: '',
        columns: [{ name: 'id', type: this.columnTypes[0] || '', size: 255, primary: true, nullable: false }]
      }
      this.createTableOpen = true
    },
    closeCreateTable () {
      this.createTableOpen = false
    },
    addColumn () {
      this.newTable.columns.push({
        name: '',
        type: this.columnTypes[0] || '',
        size: 255,
        primary: false,
        nullable: true
      })
    },
    removeColumn (index) {
      this.newTable.columns.splice(index, 1)
    },
    submitCreateTable () {
      if (!this.createTableValid) {
        return
      }
      // The server takes the column list as a JSON string and builds the DDL
      // itself -- the UI never assembles SQL.
      const columns = this.newTable.columns.map(c => ({
        name: c.name,
        type: this.columnType(c),
        primary: !!c.primary,
        nullable: !!c.nullable
      }))
      this.submitJob('createDbaasTable', {
        table: this.newTable.name,
        columns: JSON.stringify(columns)
      }).then(() => {
        this.$notification.success({
          message: this.$t('label.dbaas.console.create.table'),
          description: this.newTable.name
        })
        this.closeCreateTable()
        this.listTables()
      }).catch(error => {
        this.jobError = error?.message || String(error)
      })
    },
    checkDropEnabled () {
      getAPI('listConfigurations', { name: 'dbaas.console.drop.enabled' }).then(json => {
        const cfg = (json.listconfigurationsresponse || {}).configuration || []
        if (cfg.length > 0) {
          this.dropEnabled = String(cfg[0].value) === 'true'
        }
      }).catch(() => {
        // Not an admin (or the API is not permitted): leave it enabled and
        // let dropDbaasTable answer for itself.
      })
    },
    // Writing even a trivial SELECT by hand is the step people get stuck on,
    // so the table's own row offers it: fill the editor with a statement that
    // runs as-is and switch to the SQL tab, ready to edit or replace.
    queryTable (name) {
      this.sqlText = this.isMongo
        ? JSON.stringify({ collection: name, op: 'find', filter: {}, limit: 100 }, null, 2)
        : 'SELECT * FROM ' + this.quoteIdent(name) + ' LIMIT 100'
      this.activeTab = 'sql'
    },
    // Matches what the server does when it builds DDL: backticks on
    // mysql/mariadb, double quotes elsewhere. Names reaching here already
    // passed the server's identifier rules when the table was created.
    quoteIdent (name) {
      return /mysql|mariadb/i.test(this.resource.templatename || '')
        ? '`' + name + '`'
        : '"' + name + '"'
    },
    askDrop (name) {
      this.dropTarget = name
      this.dropConfirm = ''
    },
    cancelDrop () {
      this.dropTarget = ''
      this.dropConfirm = ''
    },
    confirmDrop () {
      const name = this.dropTarget
      // Guarded in the ok-button too; repeated here because a caller that
      // is not the button (keyboard, a future shortcut) must not bypass it.
      if (this.dropConfirm !== name) {
        return
      }
      this.submitJob('dropDbaasTable', { table: name, confirm: this.dropConfirm }).then(() => {
        this.$notification.success({
          message: this.$t('label.dbaas.console.drop'),
          description: name
        })
        this.cancelDrop()
        this.describedTable = null
        this.listTables()
      }).catch(error => {
        this.jobError = error?.message || String(error)
        this.cancelDrop()
      })
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
    onQueryKeydown (event) {
      if (event.key !== 'Enter' || (!event.ctrlKey && !event.metaKey) || event.isComposing) return
      event.preventDefault()
      this.runQuery()
    },
    runQuery () {
      if (this.submitting || !this.sqlText.trim()) return
      this.describedTable = null
      this.resultRows = []
      this.resultColumns = []
      this.resultShown = false
      this.submitJob('runDbaasQuery', { sql: this.sqlText, write: this.writeMode }).then(body => {
        this.parseResult(body)
      }).catch(error => this.fail(error))
    },
    fail (error) {
      this.resultRows = []
      this.resultColumns = []
      this.resultShown = false
      this.jobError = error.message || String(error)
    }
  }
}
</script>

<style scoped>
.database-console {
  --query-border: #d9e1ea;
  --query-editor-bg: #fafbfd;
  --query-muted: #526174;
}
.database-console-dark {
  --query-border: #434343;
  --query-editor-bg: #171c24;
  --query-muted: #b6c2d2;
}
.database-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.database-picker-label {
  color: var(--query-muted);
}
.console-note {
  margin: 8px 0;
}
.console-card {
  margin-top: 12px;
}
/* The SQL box is the one thing people want bigger, and a fixed rows= is a
   guess about their query. Let them drag it; the browser's own handle is
   enough and needs no state. */
.sql-editor {
  resize: vertical;
  min-height: 120px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 14px;
  line-height: 1.7;
  padding: 16px;
  border-radius: 0;
  border-left: 0;
  border-right: 0;
  background: var(--query-editor-bg);
}
.query-workspace {
  border: 1px solid var(--query-border);
  border-radius: 8px;
  overflow: hidden;
}
.query-toolbar,
.query-options,
.query-results-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px;
}
.query-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.query-shortcut,
.query-results-heading span {
  color: var(--query-muted);
  font-size: 12px;
}
.query-results-heading {
  padding: 16px 0 0;
}
@media (max-width: 480px) {
  .query-shortcut { display: none; }
}
/* Every table in here renders tenant data of unknown width. Without this a
   wide result pushes the panel out instead of scrolling inside it. */
.console-scroll :deep(.ant-table-wrapper),
:deep(.ant-table-wrapper) {
  max-width: 100%;
}
</style>
