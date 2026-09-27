import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ImageUploader from '../components/ImageUploader.vue'

// ---- 模块级 mock ----
vi.mock('../api', () => ({
  uploadApi: { image: vi.fn() }
}))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))
vi.mock('@element-plus/icons-vue', () => ({
  UploadFilled: { template: '<span class="upload-filled" />' }
}))

import { uploadApi } from '../api'
import { ElMessage } from 'element-plus'

// 每个用例前清空 mock 调用记录（保留 mockResolvedValue 实现），
// 避免前一个用例的 uploadApi.image 调用污染后续「不应上传」的断言
beforeEach(() => {
  vi.clearAllMocks()
})

// Element Plus 组件打桩：只保留结构，便于在 jsdom 中交互。
// 注意：el-button 不能自带 $emit('click') —— 父组件 @click 会以原生监听器兜底到根元素，
// 若再 emit 会导致同一原生 click 触发两次事件（点一次删两张/换位两次复原）。
const stubs = {
  'el-upload': { template: '<div class="el-upload-stub"><slot /></div>' },
  'el-icon': { template: '<i class="el-icon-stub"><slot /></i>' },
  'el-button': {
    template: '<button :disabled="$attrs.disabled"><slot /></button>'
  }
}

/** 模拟用户选择了一个文件（写入 input.files 并触发 change） */
async function pickFile(wrapper, file) {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

describe('ImageUploader 单图模式（limit=1，用于头像/店铺 logo）', () => {
  it('空列表：显示上传位（el-upload），不显示已选图片', () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: [], limit: 1 },
      global: { stubs }
    })
    expect(wrapper.find('.single').exists()).toBe(false)
    expect(wrapper.find('.el-upload-stub').exists()).toBe(true)
  })

  it('有图：图片直接显示在「上传位置」内，而非下方', () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: ['/uploads/avatar.png'], limit: 1 },
      global: { stubs }
    })
    const single = wrapper.find('.single')
    expect(single.exists()).toBe(true)
    expect(single.find('img').attributes('src')).toBe('/uploads/avatar.png')
    // 单图模式不渲染多图预览区
    expect(wrapper.find('.preview-list').exists()).toBe(false)
  })

  it('父组件 v-model 变化时同步显示新图', async () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: [], limit: 1 },
      global: { stubs }
    })
    expect(wrapper.find('.single').exists()).toBe(false)
    await wrapper.setProps({ modelValue: ['/uploads/later.png'] })
    expect(wrapper.find('.single').exists()).toBe(true)
    expect(wrapper.find('.single-img').attributes('src')).toBe('/uploads/later.png')
  })

  it('选择新文件：替换当前图并回传 v-model', async () => {
    uploadApi.image.mockResolvedValue('/uploads/new.png')
    const wrapper = mount(ImageUploader, {
      props: { modelValue: ['/uploads/old.png'], limit: 1 },
      global: { stubs }
    })
    await pickFile(wrapper, new File(['x'], 'new.png', { type: 'image/png' }))

    expect(uploadApi.image).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.single-img').attributes('src')).toBe('/uploads/new.png')
    expect(wrapper.emitted('update:modelValue').at(-1)[0]).toEqual(['/uploads/new.png'])
  })

  it('删除图片：回传空列表并恢复上传位', async () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: ['/uploads/avatar.png'], limit: 1 },
      global: { stubs }
    })
    await wrapper.find('.single-remove').trigger('click')

    expect(wrapper.emitted('update:modelValue').at(-1)[0]).toEqual([])
    expect(wrapper.find('.single').exists()).toBe(false)
    expect(wrapper.find('.el-upload-stub').exists()).toBe(true)
  })

  it('非图片文件：拒绝上传并提示', async () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: [], limit: 1 },
      global: { stubs }
    })
    await pickFile(wrapper, new File(['x'], 'a.txt', { type: 'text/plain' }))

    expect(uploadApi.image).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('仅支持上传图片文件')
    expect(wrapper.find('.single').exists()).toBe(false)
  })

  it('超过大小上限：拒绝上传并提示', async () => {
    // 6MB > 5MB 上限
    const big = new File([new ArrayBuffer(6 * 1024 * 1024)], 'big.png', { type: 'image/png' })
    const wrapper = mount(ImageUploader, {
      props: { modelValue: [], limit: 1, maxSizeMb: 5 },
      global: { stubs }
    })
    await pickFile(wrapper, big)

    expect(uploadApi.image).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('单张图片不能超过 5MB')
  })
})

describe('ImageUploader 多图模式（limit=9，商品图）', () => {
  it('空列表：显示拖拽上传区', () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: [], limit: 9 },
      global: { stubs }
    })
    expect(wrapper.find('.el-upload-stub').exists()).toBe(true)
    expect(wrapper.find('.preview-list').exists()).toBe(false)
  })

  it('多图预览：首张标记「封面」，第一项上移禁用、最后一项下移禁用', () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: ['/uploads/1.png', '/uploads/2.png', '/uploads/3.png'], limit: 9 },
      global: { stubs }
    })
    const items = wrapper.findAll('.preview-item')
    expect(items).toHaveLength(3)

    // 封面角标只在第一张
    expect(items[0].find('.cover-badge').exists()).toBe(true)
    expect(items[1].find('.cover-badge').exists()).toBe(false)

    // 上移/下移禁用边界
    const firstBtns = items[0].findAll('button')
    expect(firstBtns[0].attributes('disabled')).toBeDefined()  // 上移禁用
    expect(firstBtns[1].attributes('disabled')).toBeUndefined() // 下移可用
    const lastBtns = items[2].findAll('button')
    expect(lastBtns[1].attributes('disabled')).toBeDefined()   // 下移禁用
  })

  it('删除中间图片：顺序保持、回传正确列表', async () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: ['/uploads/1.png', '/uploads/2.png', '/uploads/3.png'], limit: 9 },
      global: { stubs }
    })
    const items = wrapper.findAll('.preview-item')
    await items[1].findAll('button')[2].trigger('click') // 删除第二张

    expect(wrapper.emitted('update:modelValue').at(-1)[0]).toEqual(['/uploads/1.png', '/uploads/3.png'])
    expect(wrapper.findAll('.preview-item')).toHaveLength(2)
  })

  it('下移/上移：调整顺序并回传', async () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: ['/uploads/a.png', '/uploads/b.png'], limit: 9 },
      global: { stubs }
    })
    const firstBtns = wrapper.findAll('.preview-item')[0].findAll('button')
    await firstBtns[1].trigger('click') // 第一张下移

    expect(wrapper.emitted('update:modelValue').at(-1)[0]).toEqual(['/uploads/b.png', '/uploads/a.png'])
    // 界面顺序同步
    const srcs = wrapper.findAll('.preview-img').map((i) => i.attributes('src'))
    expect(srcs).toEqual(['/uploads/b.png', '/uploads/a.png'])
  })

  it('已达上限仍选图：拒绝并提示', async () => {
    const wrapper = mount(ImageUploader, {
      props: { modelValue: ['/uploads/1.png', '/uploads/2.png'], limit: 2 },
      global: { stubs }
    })
    await pickFile(wrapper, new File(['x'], 'extra.png', { type: 'image/png' }))

    expect(uploadApi.image).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('最多上传 2 张图片')
  })
})
