import { getAPI } from '@/api'
import CreateDatabaseInstance from '@/views/compute/CreateDatabaseInstance.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/mixin', () => ({ mixinDevice: {}, mixinForm: {} }))
jest.mock('@/components/view/InfoCard', () => ({}))
jest.mock('@/views/compute/wizard/ComputeOfferingSelection', () => ({}))
jest.mock('@/views/compute/wizard/DeployButtons', () => ({}))
jest.mock('@/views/compute/wizard/DiskOfferingSelection', () => ({}))
jest.mock('@/views/compute/wizard/NetworkSelection', () => ({}))
jest.mock('@/views/compute/wizard/SshKeyPairSelection', () => ({}))
jest.mock('@/views/compute/wizard/TemplateIsoRadioGroup', () => ({}))
jest.mock('@/views/compute/wizard/ZoneBlockRadioGroupSelect', () => ({}))

beforeEach(() => jest.resetAllMocks())

it.each([true, false])('offers shared DB templates with engine API available=%s', async hasEngineApi => {
  const shared = { id: 'shared-db', name: 'dbaas-mysql-v2', displaytext: 'MySQL', isready: true, details: { 'dbaas.configdrive': 'true' } }
  const owned = { ...shared, id: 'owned-db' }
  const responses = {
    listTemplates: { listtemplatesresponse: { template: [shared, { id: 'plain', name: 'Ubuntu', isready: true }, { ...shared, id: 'not-ready', isready: false }] } },
    listZones: { listzonesresponse: { zone: [{ id: 'zone' }] } },
    listServiceOfferings: { listserviceofferingsresponse: { serviceoffering: [] } },
    listDiskOfferings: { listdiskofferingsresponse: { diskoffering: [] } },
    listDbaasEngines: { listdbaasenginesresponse: { dbaasengine: [{ template: shared.name, minmemorymb: 1024 }] } }
  }
  getAPI.mockImplementation((command, params) => Promise.resolve(command === 'listTemplates' && params.templatefilter === 'executable'
    ? { listtemplatesresponse: { template: [owned, shared] } }
    : responses[command]))
  const vm = {
    $store: { getters: { apis: hasEngineApi ? { listDbaasEngines: {} } : {} } },
    form: {}, fetchNetworks: jest.fn(), $notifyError: jest.fn()
  }
  CreateDatabaseInstance.methods.fetchOptions.call(vm)
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(getAPI.mock.calls.find(([command]) => command === 'listTemplates')[1].templatefilter).toBe('sharedexecutable')
  expect(getAPI.mock.calls.filter(([command]) => command === 'listTemplates').map(([, params]) => params.templatefilter)).toEqual(['sharedexecutable', 'executable'])
  expect(vm.templates.map(template => template.id)).toEqual(['shared-db', 'owned-db'])
  expect(vm.templates[0].engineLabel).toBe('MySQL')
  expect(vm.form.zoneid).toBe('zone')
  expect(vm.$notifyError).not.toHaveBeenCalled()
  expect(vm.optionsLoading).toBe(false)
})

it('still offers an owned engine when no templates have been shared', async () => {
  const owned = { id: 'owned-db', name: 'dbaas-mysql-v2', displaytext: 'MySQL', isready: true, details: { 'dbaas.configdrive': 'true' } }
  getAPI.mockImplementation((command, params) => Promise.resolve(command === 'listTemplates'
    ? { listtemplatesresponse: { template: params.templatefilter === 'executable' ? [owned] : [] } }
    : ({
        listZones: { listzonesresponse: { zone: [{ id: 'zone' }] } },
        listServiceOfferings: { listserviceofferingsresponse: {} },
        listDiskOfferings: { listdiskofferingsresponse: {} },
        listDbaasEngines: { listdbaasenginesresponse: { dbaasengine: [{ template: owned.name }] } }
      })[command]))
  const vm = { $store: { getters: { apis: { listDbaasEngines: {} } } }, form: {}, fetchNetworks: jest.fn(), $notifyError: jest.fn() }
  CreateDatabaseInstance.methods.fetchOptions.call(vm)
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(vm.templates.map(template => template.id)).toEqual(['owned-db'])
  expect(vm.$notifyError).not.toHaveBeenCalled()
})
