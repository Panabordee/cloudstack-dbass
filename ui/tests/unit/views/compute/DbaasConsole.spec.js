import { getAPI } from '@/api'
import DbaasConsole from '@/views/compute/DbaasConsole.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const context = () => ({
  ...DbaasConsole.data.call({ $t: key => key }),
  $t: key => key
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
