import { getAPI } from '@/api'
import DbaasQuery from '@/views/compute/DbaasQuery.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/components/widgets/Breadcrumb', () => ({}))

const context = () => {
  const vm = {
    ...DbaasQuery.data(),
    instances: [{ id: 'a', templatename: 'dbaas-mysql-v2' }, { id: 'b', templatename: 'dbaas-postgresql-v2' }],
    engines: [{ template: 'dbaas-mysql-v2' }, { template: 'dbaas-postgresql-v2' }]
  }
  Object.defineProperty(vm, 'selectedInstance', { get: () => DbaasQuery.computed.selectedInstance.call(vm) })
  vm.engineTypeOf = DbaasQuery.methods.engineTypeOf.bind(vm)
  return vm
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0))

beforeEach(() => jest.resetAllMocks())

it('keeps the current database selection when an older request resolves last', async () => {
  let resolveA, resolveB
  getAPI.mockReturnValueOnce(new Promise(resolve => { resolveA = resolve }))
    .mockReturnValueOnce(new Promise(resolve => { resolveB = resolve }))
  const vm = context()
  vm.form.instanceId = 'a'
  DbaasQuery.methods.onInstanceChange.call(vm)
  vm.form.instanceId = 'b'
  DbaasQuery.methods.onInstanceChange.call(vm)
  resolveB({ listdbaasdatabasesresponse: { dbaasdatabase: [{ database: 'db_b', status: 'confirmed' }] } })
  await flush()
  resolveA({ listdbaasdatabasesresponse: { dbaasdatabase: [{ database: 'db_a', status: 'confirmed' }] } })
  await flush()
  expect(vm.form.database).toBe('db_b')
  expect(vm.databases.map(db => db.database)).toEqual(['db_b'])
  expect(vm.form.engineType).toBe('postgresql')
})

it('detects the selected engine when engine discovery finishes after selection', async () => {
  let resolveEngines
  getAPI.mockReturnValue(new Promise(resolve => { resolveEngines = resolve }))
  const vm = context()
  vm.engines = []
  vm.form.instanceId = 'a'
  vm.form.engineType = ''
  const pending = DbaasQuery.methods.fetchEngines.call(vm)
  resolveEngines({ listdbaasenginesresponse: { dbaasengine: [{ template: 'dbaas-mysql-v2' }] } })
  await pending
  expect(vm.form.engineType).toBe('mysql')
})

it('clears a removed selection without accepting its pending response', async () => {
  let resolveDatabases
  getAPI.mockReturnValue(new Promise(resolve => { resolveDatabases = resolve }))
  const vm = context()
  vm.form.instanceId = 'a'
  DbaasQuery.methods.onInstanceChange.call(vm)
  vm.form.instanceId = undefined
  DbaasQuery.methods.onInstanceChange.call(vm)
  expect(vm.databasesLoading).toBe(false)
  resolveDatabases({ listdbaasdatabasesresponse: { dbaasdatabase: [{ database: 'old_db' }] } })
  await flush()
  expect(vm.databases).toEqual([])
  expect(vm.form.database).toBeUndefined()
})
