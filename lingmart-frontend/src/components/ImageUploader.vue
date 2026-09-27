<template>
  <div class="image-uploader">
    <!-- 单图模式：图片显示在「上传位置」，点击可更换/删除 -->
    <div v-if="props.limit === 1 && list.length" class="single" @click="openPicker">
      <img :src="list[0]" class="single-img" />
      <div class="single-overlay">
        <span>点击更换</span>
      </div>
      <span class="single-remove" @click.stop="remove(0)">删除</span>
    </div>

    <el-upload
      v-else
      drag
      multiple
      accept="image/*"
      :show-file-list="false"
      :before-upload="beforeUpload"
      :http-request="doUpload"
      class="drop"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">拖拽图片到此处，或 <em>点击上传</em></div>
      <template #tip>
        <div class="el-upload__tip">第一张为封面；最多 {{ limit }} 张，单张 ≤ {{ maxSizeMb }}MB</div>
      </template>
    </el-upload>

    <!-- 单图模式的隐藏文件选择框 -->
    <input ref="fileInput" type="file" accept="image/*" style="display:none" @change="onPick" />

    <div v-if="props.limit !== 1 && list.length" class="preview-list">
      <div v-for="(url, i) in list" :key="url" class="preview-item">
        <img :src="url" class="preview-img" />
        <span v-if="i === 0" class="cover-badge">封面</span>
        <div class="ops">
          <el-button size="small" link :disabled="i === 0" @click="move(i, -1)">上移</el-button>
          <el-button size="small" link :disabled="i === list.length - 1" @click="move(i, 1)">下移</el-button>
          <el-button size="small" link type="danger" @click="remove(i)">删除</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { uploadApi } from '../api'

const props = defineProps({
  // 有序图片地址数组，第一张为封面（v-model 双向绑定）
  modelValue: { type: Array, default: () => [] },
  // 最多张数
  limit: { type: Number, default: 9 },
  // 单张大小上限（MB）
  maxSizeMb: { type: Number, default: 5 }
})

const emit = defineEmits(['update:modelValue'])

const list = ref([...props.modelValue])
const fileInput = ref(null)

// 上传中标记：多张并发上传时，忽略父组件 v-model 的同步回环（避免中途被旧值覆盖丢图）
let uploading = false

// 父组件 v-model 变化时同步到本地；仅在非本组件上传产生时应用
watch(() => props.modelValue, (v) => {
  if (!uploading) list.value = [...(v || [])]
}, { deep: true })

/** 单图模式：点击已上传图片 → 弹出文件选择 */
function openPicker() {
  fileInput.value?.click()
}

/** 单图模式：选择文件后校验并上传，替换第一张 */
async function onPick(e) {
  const file = e.target.files?.[0]
  e.target.value = '' // 允许重复选同一文件
  if (!file) return
  if (!beforeUpload(file)) return
  uploading = true
  try {
    const url = await uploadApi.image(file)
    list.value = [url]
    emit('update:modelValue', [...list.value])
  } catch (err) {
    ElMessage.error('上传失败，请重试')
  } finally {
    uploading = false
  }
}

/** 上传前校验：仅图片、大小不超限 */
function beforeUpload(file) {
  if (file.type && !file.type.startsWith('image/')) {
    ElMessage.error('仅支持上传图片文件')
    return false
  }
  if (file.size > props.maxSizeMb * 1024 * 1024) {
    ElMessage.error(`单张图片不能超过 ${props.maxSizeMb}MB`)
    return false
  }
  // 单图模式（limit=1）的「点击更换」语义是替换而非追加，不做数量拦截；
  // 多图模式已达上限时拦截
  if (props.limit !== 1 && list.value.length >= props.limit) {
    ElMessage.warning(`最多上传 ${props.limit} 张图片`)
    return false
  }
  return true
}

/** 自定义上传：调用统一上传接口，成功后把 URL 追加到列表尾部 */
function doUpload({ file, onSuccess, onError }) {
  uploading = true
  uploadApi.image(file)
    .then((url) => {
      list.value.push(url)
      emit('update:modelValue', [...list.value])
      onSuccess(url)
    })
    .catch((err) => {
      onError(err)
    })
    .finally(() => {
      uploading = false
    })
}

function move(index, delta) {
  const target = index + delta
  if (target < 0 || target >= list.value.length) return
  const arr = [...list.value]
  ;[arr[index], arr[target]] = [arr[target], arr[index]]
  list.value = arr
  emit('update:modelValue', [...arr])
}

function remove(index) {
  const arr = [...list.value]
  arr.splice(index, 1)
  list.value = arr
  emit('update:modelValue', [...arr])
}
</script>

<style scoped>
.image-uploader .drop { max-width: 100%; }
/* 单图模式：预览图即上传位（居中显示，点击更换） */
.single { position: relative; width: 120px; height: 120px; border-radius: 8px; overflow: hidden; cursor: pointer; border: 1px solid #e4e7ed; background: #fafafa; }
.single-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.single-overlay { position: absolute; left: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, .45); color: #fff; font-size: 12px; text-align: center; padding: 4px 0; }
.single-remove { position: absolute; top: 4px; right: 4px; background: rgba(0, 0, 0, .5); color: #fff; font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.preview-list { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.preview-item { position: relative; width: 132px; border: 1px solid #e4e7ed; border-radius: 6px; overflow: hidden; background: #fafafa; }
.preview-img { width: 132px; height: 132px; object-fit: cover; display: block; }
.cover-badge { position: absolute; top: 4px; left: 4px; background: #409eff; color: #fff; font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.ops { display: flex; flex-wrap: wrap; justify-content: center; gap: 0 4px; padding: 3px 2px; font-size: 12px; }
.ops :deep(.el-button) { padding: 3px 4px; }
</style>
