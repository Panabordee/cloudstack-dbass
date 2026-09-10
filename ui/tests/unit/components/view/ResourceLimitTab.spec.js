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

import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import ResourceLimitTab from '@/components/view/ResourceLimitTab'

import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({
  getAPI: jest.fn(),
  postAPI: jest.fn()
}))

const listResourceLimitsResponse = (limits) => ({
  listresourcelimitsresponse: {
    resourcelimit: limits
  }
})

const domainResource = { id: 'domain-uuid-1', name: 'child-domain', level: 1 }

const factory = (opts = {}) => {
  return common.createFactory(ResourceLimitTab, {
    store: common.createMockStore({
      user: { apis: { updateResourceLimit: {} } }
    }),
    i18n: common.createMockI18n('en', {
      label: { max: 'Max', submit: 'Submit' },
      message: { 'apply.success': 'success', 'request.failed': 'failed' }
    }),
    props: { resource: domainResource },
    mocks: {
      $route: {
        path: '/domain',
        meta: { name: 'domain' }
      }
    }
  })
}

const mockUntaggedLimit = (max) => ({
  resourcetype: '0',
  resourcetypename: 'instance',
  max: max,
  account: 'child-account',
  domain: 'child-domain',
  domainid: domainResource.id
})

describe('Components > View > ResourceLimitTab.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'error').mockImplementation(() => {})
    postAPI.mockResolvedValue({ updateresourcelimitresponse: {} })
  })

  describe('fetchData()', () => {
    it('displays a stored limit of 0 as 0', async () => {
      getAPI.mockResolvedValue(listResourceLimitsResponse([mockUntaggedLimit(0)]))
      const wrapper = factory()
      await flushPromises()

      const input = wrapper.find('.ant-input-number-input')
      expect(input.element.value).toBe('0')
    })

    it('displays no stored limit as unlimited (-1)', async () => {
      getAPI.mockResolvedValue(listResourceLimitsResponse([mockUntaggedLimit(null)]))
      const wrapper = factory()
      await flushPromises()

      const input = wrapper.find('.ant-input-number-input')
      expect(input.element.value).toBe('-1')
    })

    it('displays tagged limit of 0 as 0', async () => {
      getAPI.mockResolvedValue(listResourceLimitsResponse([
        mockUntaggedLimit(-1),
        { ...mockUntaggedLimit(0), tag: 'tag-a' }
      ]))
      const wrapper = factory()
      await flushPromises()

      await wrapper.find('.tagged-limit-collapse .ant-collapse-header').trigger('click')
      await flushPromises()

      const input = wrapper.find('.tagged-limit-collapse .ant-input-number-input')
      expect(input.element.value).toBe('0')
    })
  })

  describe('handleSubmit()', () => {
    it('submits max=-1 for updateResourceLimit when the current stored limit is 0 and -1 is entered', async () => {
      getAPI.mockResolvedValue(listResourceLimitsResponse([mockUntaggedLimit(0)]))
      const wrapper = factory()
      await flushPromises()
      postAPI.mockClear()

      const input = wrapper.find('.ant-input-number-input')
      await input.setValue('-1')
      await wrapper.find('.card-footer button').trigger('click')
      await flushPromises()

      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI).toHaveBeenCalledWith('updateResourceLimit', {
        domainid: domainResource.id,
        resourcetype: '0',
        max: -1
      })
    })

    it('does not submit anything when no value is changed', async () => {
      getAPI.mockResolvedValue(listResourceLimitsResponse([mockUntaggedLimit(5)]))
      const wrapper = factory()
      await flushPromises()
      postAPI.mockClear()

      await wrapper.find('.card-footer button').trigger('click')
      await flushPromises()

      expect(postAPI).not.toHaveBeenCalled()
    })

    it('submits a changed positive limit', async () => {
      getAPI.mockResolvedValue(listResourceLimitsResponse([mockUntaggedLimit(5)]))
      const wrapper = factory()
      await flushPromises()
      postAPI.mockClear()

      const input = wrapper.find('.ant-input-number-input')
      await input.setValue('10')
      await wrapper.find('.card-footer button').trigger('click')
      await flushPromises()

      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI).toHaveBeenCalledWith('updateResourceLimit', {
        domainid: domainResource.id,
        resourcetype: '0',
        max: 10
      })
    })

    it('submits tag and max for tagged limits', async () => {
      getAPI.mockResolvedValue(listResourceLimitsResponse([
        mockUntaggedLimit(10),
        { ...mockUntaggedLimit(10), tag: 'tag-a' }
      ]))
      const wrapper = factory()
      await flushPromises()
      postAPI.mockClear()

      await wrapper.find('.tagged-limit-collapse .ant-collapse-header').trigger('click')
      await flushPromises()

      const input = wrapper.find('.tagged-limit-collapse .ant-input-number-input')
      await input.setValue('-1')
      await wrapper.find('.card-footer button').trigger('click')
      await flushPromises()

      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI).toHaveBeenCalledWith('updateResourceLimit', {
        domainid: domainResource.id,
        resourcetype: '0',
        tag: 'tag-a',
        max: -1
      })
    })
  })
})
