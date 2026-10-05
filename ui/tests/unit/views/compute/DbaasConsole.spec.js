import { getAPI } from '@/api'
import DbaasConsole from '@/views/compute/DbaasConsole.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const context = () => ({
  ...DbaasConsole.data.call({ $t: key => key }),
  $t: key => key
})

it.each([
  ['mysql', 'SELECT * FROM `order` LIMIT 100'],
  ['mariadb', 'SELECT * FROM `order` LIMIT 100'],
  ['postgresql', 'SELECT * FROM "order" LIMIT 100']
])('opens a runnable preview for a reserved table name on %s', (engine, query) => {
  const vm = { resource: { templatename: `dbaas-${engine}-v2` }, activeTab: 'tables', submitJob: jest.fn() }
  vm.isMongo = DbaasConsole.computed.isMongo.call(vm)
  vm.quoteIdent = name => DbaasConsole.methods.quoteIdent.call(vm, name)
  DbaasConsole.methods.queryTable.call(vm, 'order')
  expect(vm.sqlText).toBe(query)
  expect(vm.activeTab).toBe('sql')
  expect(vm.submitJob).not.toHaveBeenCalled()
})

it('opens a MongoDB collection with a JSON command instead of SQL', () => {
  const vm = { resource: { templatename: 'dbaas-mongodb-v2' }, activeTab: 'tables', submitJob: jest.fn() }
  vm.isMongo = DbaasConsole.computed.isMongo.call(vm)
  DbaasConsole.methods.queryTable.call(vm, 'orders')
  expect(JSON.parse(vm.sqlText)).toEqual({ collection: 'orders', op: 'find', filter: {}, limit: 100 })
  expect(DbaasConsole.computed.sqlEditorLabel.call(vm)).toBe('label.dbaas.console.sql.editor.mongo')
  expect(vm.activeTab).toBe('sql')
  expect(vm.submitJob).not.toHaveBeenCalled()
})

describe('DBaaS console result handling', () => {
  beforeEach(() => jest.resetAllMocks())

  it('rejects a failed database job with the agent error', async () => {
    getAPI.mockResolvedValue({ getdbaasjobresultresponse: {
      dbaasjobresult: { state: 'failed', error: 'INSERT command denied' }
    } })
    await expect(DbaasConsole.methods.pollResult.call(context(), 'job', 0))
      .rejects.toThrow('INSERT command denied')
  })

  it('keeps separate values when result columns share a name', () => {
    const vm = context()
    DbaasConsole.methods.parseResult.call(vm, {
      result: JSON.stringify({ columns: ['same', 'same'], rows: [[1, 2]] })
    })
    expect(vm.resultColumns.map(column => column.title)).toEqual(['same', 'same'])
    expect(vm.resultColumns.map(column => vm.resultRows[0][column.dataIndex])).toEqual([1, 2])
  })

  it('removes previous query rows when a later operation fails', () => {
    const vm = context()
    vm.resultRows = [{ previous: 'stale' }]
    vm.resultColumns = [{ dataIndex: 'previous' }]
    vm.resultShown = true
    DbaasConsole.methods.fail.call(vm, new Error('query rejected'))
    expect(vm.resultRows).toEqual([])
    expect(vm.resultColumns).toEqual([])
    expect(vm.resultShown).toBe(false)
    expect(vm.jobError).toBe('query rejected')
  })
})

it('converts a parameterised type to an SQL type with the requested length', () => {
  expect(DbaasConsole.methods.columnType({ type: 'VARCHAR(n)', size: 120 })).toBe('VARCHAR(120)')
  expect(DbaasConsole.methods.columnType({ type: 'NUMERIC(n)', size: 10 })).toBe('NUMERIC(10)')
  expect(DbaasConsole.methods.columnType({ type: 'INT' })).toBe('INT')
})

it('refuses a missing or invalid parameter instead of sending literal n', () => {
  for (const size of [undefined, 0, -1, 1.5, 65536]) {
    expect(DbaasConsole.methods.columnType({ type: 'VARCHAR(n)', size })).toBe('')
  }
})

it.each(['ctrlKey', 'metaKey'])('runs the editor with %s + Enter', modifier => {
  const vm = { runQuery: jest.fn() }
  const event = { key: 'Enter', [modifier]: true, preventDefault: jest.fn() }
  DbaasConsole.methods.onQueryKeydown.call(vm, event)
  expect(event.preventDefault).toHaveBeenCalledTimes(1)
  expect(vm.runQuery).toHaveBeenCalledTimes(1)
})

it('keeps plain Enter and IME composition in the editor', () => {
  const vm = { runQuery: jest.fn() }
  for (const event of [{ key: 'Enter' }, { key: 'Enter', ctrlKey: true, isComposing: true }]) {
    DbaasConsole.methods.onQueryKeydown.call(vm, event)
  }
  expect(vm.runQuery).not.toHaveBeenCalled()
})

it.each([{ sqlText: '  ', submitting: false }, { sqlText: 'SELECT 1', submitting: true }])('does not queue an empty query or duplicate in-flight query', state => {
  const vm = { ...state, submitJob: jest.fn() }
  DbaasConsole.methods.runQuery.call(vm)
  expect(vm.submitJob).not.toHaveBeenCalled()
})
