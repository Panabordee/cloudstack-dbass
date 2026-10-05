import { getAPI } from '@/api'
import DatabaseDetails from '@/views/compute/DatabaseDetails.vue'
import DbaasConsole from '@/views/compute/DbaasConsole.vue'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/components/widgets/Status.vue', () => ({}))
jest.mock('@/views/compute/CreateDatabase.vue', () => ({}))
jest.mock('@/views/compute/ResetDatabasePassword.vue', () => ({}))
jest.mock('@/views/compute/ShowDatabasePassword.vue', () => ({}))
const context = () => ({ ...DatabaseDetails.data(), $route: { params: { id: 'db-vm' } } })
beforeEach(() => jest.resetAllMocks())
it('opens the query editor immediately', () => {
  expect(DatabaseDetails.data().tab).toBe('query')
  expect(DbaasConsole.data.call({ $t: key => key, initialTab: 'sql' }).activeTab).toBe('sql')
})
it('loads only the selected service and its databases without fetching passwords', async () => {
  getAPI.mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'db-vm', templatename: 'dbaas-mysql-v2' }] } })
    .mockResolvedValueOnce({ listdbaasdatabasesresponse: { dbaasdatabase: [{ database: 'one', status: 'confirmed' }, { database: 'two' }] } })
    .mockResolvedValueOnce({ listdbaasenginesresponse: { dbaasengine: [] } })
  const vm = context()
  await DatabaseDetails.methods.fetchData.call(vm)
  expect(vm.resource.id).toBe('db-vm')
  expect(vm.databases.map(d => d.database)).toEqual(['one', 'two'])
  expect(getAPI.mock.calls.map(c => c[0])).toEqual(['listVirtualMachines', 'listDbaasDatabases', 'listDbaasEngines'])
  expect(getAPI.mock.calls[0][1]).toEqual({ id: 'db-vm' })
})
it('does not present a normal VM as a database', async () => {
  getAPI.mockResolvedValue({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'db-vm', templatename: 'Ubuntu' }] } })
  const vm = context()
  await DatabaseDetails.methods.fetchData.call(vm)
  expect(vm.resource).toEqual({})
  expect(vm.error).toMatch(/access denied/)
  expect(getAPI).toHaveBeenCalledTimes(1)
})
it('shows the reachable VPN endpoint', () => {
  expect(DatabaseDetails.computed.endpoint.call({ resource: { ipaddress: '192.168.240.12' }, engineType: 'mysql', $config: { dbaas: { connectionHosts: { '192.168.240.12': '10.60.2.15' } } } })).toBe('10.60.2.15:3306')
})

it('opens a selected database in the query tab', () => {
  const vm = { tab: 'databases', openedDatabase: '' }
  DatabaseDetails.methods.openDatabase.call(vm, 'two')
  expect(vm.openedDatabase).toBe('two')
  expect(vm.tab).toBe('query')
})

it('keeps the query workspace when closing connection details', () => {
  const vm = { action: 'password', tab: 'query', openedDatabase: 'two', fetchData: jest.fn() }
  DatabaseDetails.methods.closeAction.call(vm)
  expect(vm.action).toBe('')
  expect(vm.openedDatabase).toBe('two')
  expect(vm.fetchData).not.toHaveBeenCalled()
})
