<template>
  <div class="card">
    <div class="head">
      <h3 class="page-title">收货地址</h3>
      <el-button type="primary" @click="openCreate">新增地址</el-button>
    </div>

    <el-empty v-if="!loading && addresses.length === 0" description="还没有收货地址，先添加一个吧" />

    <div class="addr-list">
      <div v-for="a in addresses" :key="a.id" class="addr" :class="{ default: a.isDefault }">
        <div class="line1">
          <span class="name">{{ a.receiverName }}</span>
          <span class="phone">{{ a.receiverPhone }}</span>
          <el-tag v-if="a.isDefault" size="small" type="success">默认</el-tag>
        </div>
        <div class="line2">{{ a.fullAddress }}</div>
        <div class="ops">
          <el-button v-if="!a.isDefault" size="small" @click="setDefault(a)">设为默认</el-button>
          <el-button size="small" @click="openEdit(a)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(a)">删除</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑地址' : '新增地址'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="收货人" prop="receiverName">
          <el-input v-model="form.receiverName" maxlength="50" placeholder="收货人姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="receiverPhone">
          <el-input v-model="form.receiverPhone" maxlength="11" placeholder="11 位手机号" />
        </el-form-item>
        <el-form-item label="省" prop="province">
          <el-input v-model="form.province" maxlength="50" placeholder="如：浙江省" />
        </el-form-item>
        <el-form-item label="市" prop="city">
          <el-input v-model="form.city" maxlength="50" placeholder="如：杭州市" />
        </el-form-item>
        <el-form-item label="区/县" prop="district">
          <el-input v-model="form.district" maxlength="50" placeholder="如：西湖区" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detailAddress">
          <el-input v-model="form.detailAddress" type="textarea" :rows="2" maxlength="255"
                    show-word-limit placeholder="街道、门牌号" />
        </el-form-item>
        <el-form-item label="默认地址">
          <el-switch v-model="form.isDefault" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addressApi } from '../api'

const addresses = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)
const formRef = ref(null)

const form = reactive({
  receiverName: '',
  receiverPhone: '',
  province: '',
  city: '',
  district: '',
  detailAddress: '',
  isDefault: false
})

// 与后端 AddressDTO 的校验规则保持一致：前端先拦一道，避免无谓的往返
const rules = {
  receiverName: [{ required: true, message: '请填写收货人姓名', trigger: 'blur' }],
  receiverPhone: [
    { required: true, message: '请填写手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  province: [{ required: true, message: '请填写省份', trigger: 'blur' }],
  city: [{ required: true, message: '请填写城市', trigger: 'blur' }],
  district: [{ required: true, message: '请填写区县', trigger: 'blur' }],
  detailAddress: [{ required: true, message: '请填写详细地址', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    addresses.value = await addressApi.list()
  } finally {
    loading.value = false
  }
}

function resetForm(source) {
  Object.assign(form, {
    receiverName: source?.receiverName || '',
    receiverPhone: source?.receiverPhone || '',
    province: source?.province || '',
    city: source?.city || '',
    district: source?.district || '',
    detailAddress: source?.detailAddress || '',
    isDefault: !!source?.isDefault
  })
}

function openCreate() {
  editingId.value = null
  resetForm(null)
  dialogVisible.value = true
}

function openEdit(a) {
  editingId.value = a.id
  resetForm(a)
  dialogVisible.value = true
}

async function save() {
  if (!formRef.value) return
  await formRef.value.validate(async (ok) => {
    if (!ok) return
    saving.value = true
    try {
      if (editingId.value) {
        await addressApi.update(editingId.value, { ...form })
      } else {
        await addressApi.create({ ...form })
      }
      ElMessage.success('已保存')
      dialogVisible.value = false
      await load()
    } finally {
      saving.value = false
    }
  })
}

async function setDefault(a) {
  await addressApi.setDefault(a.id)
  ElMessage.success('已设为默认地址')
  await load()
}

async function remove(a) {
  await ElMessageBox.confirm(`确认删除「${a.receiverName} ${a.fullAddress}」这个地址吗？`, '删除地址', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  await addressApi.remove(a.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.addr-list { display: flex; flex-direction: column; gap: 12px; margin-top: 16px; }
.addr { border: 1px solid #ebeef5; border-radius: 6px; padding: 14px 16px; }
.addr.default { border-color: #67c23a; background: #f0f9eb; }
.line1 { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.name { font-weight: 600; }
.phone { color: #606266; }
.line2 { color: #303133; margin-bottom: 10px; }
.ops { display: flex; gap: 8px; }
</style>
