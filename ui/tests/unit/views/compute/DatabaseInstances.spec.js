import { postAPI } from '@/api'
import DatabaseInstances from '@/views/compute/DatabaseInstances.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/components/view/ActionButton.vue', () => ({}))
jest.mock('@/components/widgets/Breadcrumb.vue', () => ({}))
jest.mock('@/components/widgets/Status.vue', () => ({}))
jest.mock('@/views/compute/CreateDatabase.vue', () => ({}))
jest.mock('@/views/compute/ResetDatabasePassword.vue', () => ({}))
jest.mock('@/views/compute/ShowDatabasePassword.vue', () => ({}))
jest.mock('@/utils/zone', () => ({ isZoneCreated: jest.fn() }))

const runDestroy = async expunge => {
  let poll
  const vm = {
    $t: key => key,
    fetchDataDiskIds: jest.fn().mockResolvedValue([]),
    deleteDataDisks: jest.fn(),
    fetchData: jest.fn(),
    $notifyError: jest.fn(),
    $pollJob: options => { poll = options }
  }
  postAPI.mockResolvedValue({ destroyvirtualmachineresponse: { jobid: 'job' } })
  await DatabaseInstances.methods.destroyInstance.call(vm, { id: 'vm', uuid: 'vm', name: 'test' }, expunge)
  poll.successMethod()
  await Promise.resolve()
}

beforeEach(() => jest.resetAllMocks())

it('retains credentials when the instance remains recoverable', async () => {
  await runDestroy(false)
  expect(postAPI.mock.calls.map(call => call[0])).toEqual(['destroyVirtualMachine'])
})

it('cleans up credentials after an irreversible expunge succeeds', async () => {
  await runDestroy(true)
  expect(postAPI.mock.calls.map(call => call[0])).toEqual(['destroyVirtualMachine', 'deleteDbaasCredentials'])
})
