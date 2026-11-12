<template>
  <div class="card" style="max-width: 720px">
    <h3 class="page-title">申请开店</h3>
    <!-- 助手预填提示（v0.28）：交代草稿来源、有几条要补全、以及"提交要你自己点" -->
    <el-alert
      v-if="prefillNotice"
      class="prefill-alert"
      type="success"
      show-icon
      title="已按你提供的清单预填草稿"
      :description="prefillNotice"
      @close="prefillNotice = ''"
    />
    <el-form :model="form" label-width="90px">
      <el-form-item label="店铺名称">
        <el-input v-model="form.shopName" maxlength="50" placeholder="如：小王的杂货铺" />
      </el-form-item>
      <el-form-item label="店铺 logo">
        <ImageUploader v-model="form.shopLogo" :limit="1" />
      </el-form-item>
      <el-form-item label="店铺描述">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" placeholder="主营方向 / 特色" />
      </el-form-item>
      <!-- 履约配置：自营实体店以到店自提为主，地址是买家取货点 -->
      <el-form-item label="门店地址">
        <el-input v-model="form.address" maxlength="255" placeholder="买家到店自提的地址，如：杭州市西湖区文一西路 1 号" />
      </el-form-item>
      <el-form-item label="门店电话">
        <el-input v-model="form.contactPhone" maxlength="20" placeholder="手机号或带区号固话（买家取货/售后联系用）" />
      </el-form-item>
      <el-form-item label="支持快递">
        <el-switch v-model="form.supportExpress" />
        <span class="hint">关闭时仅支持到店自提（大多数实体店的默认选择）</span>
      </el-form-item>

      <el-divider content-position="left">要卖的东西（申请审核通过后才可销售，至少 1 个完整商品）</el-divider>
      <div v-for="(p, i) in form.products" :key="i" class="p-card">
        <div class="p-head">
          <b>商品 {{ i + 1 }}</b>
          <el-button link type="danger" @click="form.products.splice(i, 1)">移除</el-button>
        </div>
        <!-- 不能写 v-model="p"：p 是 v-for 的作用域变量，不可赋值，编译期即报
             "v-model cannot be used on v-for or v-slot scope variables"。
             读用别名、写回数组元素（成员表达式可写），语义等价于 v-model。 -->
        <ProductEditor :model-value="p" @update:model-value="form.products[i] = $event" />
      </div>
      <el-button size="small" @click="addProduct">+ 再添加一个商品</el-button>

      <el-form-item style="margin-top: 20px">
        <el-button type="primary" :loading="submitting" @click="submit">提交申请</el-button>
        <el-button @click="$router.push('/')">返回首页</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { shopApi } from '../api'
import { useCopilotPrefillStore } from '../store/copilotPrefill'
import ImageUploader from '../components/ImageUploader.vue'
import ProductEditor from '../components/ProductEditor.vue'

const router = useRouter()
const prefillStore = useCopilotPrefillStore()
const submitting = ref(false)

/** 助手预填后的提示文案（v0.28）：让用户知道草稿哪来的、有几条要补全、提交要自己点 */
const prefillNotice = ref('')
const form = ref({
  shopName: '',
  description: '',
  shopLogo: [],   // 单张 logo（上传组件返回数组，取第一张）
  // 履约配置：自提是默认方式，快递由店主决定是否开启
  address: '',
  contactPhone: '',
  supportExpress: false,
  products: [newProduct()]
})

function newProduct() {
  // 与卖家中心「发布商品」同一套结构：标题/副标题/图片/详情 + 可增删的多规格（数量留空=不限量）
  return { title: '', subtitle: '', images: [], description: '', skus: [{ title: '', price: null, qty: null }] }
}

function addProduct() {
  form.value.products.push(newProduct())
}

/** 判断单个商品是否填写完整（标题 + 至少一个有效规格：规格名 + 价格） */
function isValidProduct(p) {
  return !!(p.title && (p.skus || []).some(s => s.title && s.price != null && Number(s.price) > 0))
}

/**
 * 消费助手交来的预填草稿（v0.28）。
 *
 * <p>用 {@code watch} + {@code immediate} 而不是只在 {@code onMounted} 里取：
 * 用户点【填入表单】时**可能已经在本页**（组件不会再挂载），也可能是从别的页面跳过来的（挂载时取）。
 * 两种都要接住。</p>
 *
 * <p>直接覆盖表单内容、不再弹二次确认：填入是用户刚刚在卡片上点过的**主动动作**，
 * 而且覆盖的只是他自己尚未提交的草稿。项目在 Agent 安全设计里明确写过「确认疲劳是真实存在的」，
 * 为一个可撤销的本地草稿再叠一层弹窗，只会稀释真正重要的那次确认。</p>
 */
watch(() => prefillStore.draft, (draft) => {
  if (!draft) {
    return
  }
  prefillStore.take()
  applyPrefill(draft)
}, { immediate: true })

/** 把草稿写进表单。图片一律留空——图片必须是已上传的 URL，助手生成不了，由用户自己传 */
function applyPrefill(draft) {
  if (draft.shopName) {
    form.value.shopName = draft.shopName
  }
  if (draft.description) {
    form.value.description = draft.description
  }
  const products = (draft.products || []).map(p => ({
    title: p.title || '',
    subtitle: p.subtitle || '',
    images: [],
    description: p.description || '',
    skus: (p.skus || []).map(s => ({
      title: s.title || '',
      price: s.price == null || s.price === '' ? null : Number(s.price),
      qty: s.qty == null || s.qty === '' ? null : Number(s.qty)
    }))
  }))
  if (products.length > 0) {
    form.value.products = products
  }
  // 逐条统计"信息不全"：静默丢弃缺价格的条目会让用户察觉不到少了一条，如实提示更好
  const incomplete = products.filter(p => !isValidProduct(p)).length
  prefillNotice.value = `已按你提供的清单预填 ${products.length} 条商品`
    + (incomplete > 0 ? `，其中 ${incomplete} 条信息不全（缺标题 / 规格名 / 价格），需补全后才能提交` : '')
    + '。商品图片需要你自己上传。请核对标题与价格后，自己点「提交申请」。'
  ElMessage.info('已填入助手解析的草稿，请核对后再提交')
}

/** 把共享表单结构转换为后端 SpuDTO（第一张图为主图，其余图挂到各 SKU；数量 NULL=不限量） */
function toSpuDTO(p) {
  const images = p.images || []
  const skus = (p.skus || [])
    .filter(s => s.title && s.price != null && Number(s.price) > 0)
    .map(s => ({ title: s.title, price: s.price, stock: s.qty == null ? null : s.qty, images: images.slice(1) }))
  return {
    title: p.title,
    subtitle: p.subtitle || null,
    mainImage: images[0] || null,
    description: p.description || null,
    skus
  }
}

/** 申请开店页只处理「创建申请」：已有有效申请（待审/已通过）则跳转「我的店铺」查看审核情况 */
async function load() {
  try {
    const app = await shopApi.myApplication()
    if (app && app.status !== 2) {
      ElMessage.info('你已有进行中的开店申请，请到「我的店铺」查看审核情况')
      router.replace('/my-shop')
    }
    // 被拒绝（status=2）或从未申请：显示申请表，可提交/重新申请
  } catch (e) {
    // 查询失败不阻塞，保持表单可填
  }
}

async function submit() {
  if (submitting.value) return
  if (!form.value.shopName) {
    ElMessage.warning('请填写店铺名称')
    return
  }
  if (!form.value.address) {
    ElMessage.warning('请填写门店地址（买家自提时要按这个地址取货）')
    return
  }
  if (!form.value.contactPhone) {
    ElMessage.warning('请填写门店电话')
    return
  }
  const validProducts = form.value.products.filter(isValidProduct).map(toSpuDTO)
  if (validProducts.length === 0) {
    ElMessage.warning('请至少填写一个完整商品（标题 + 规格名 + 价格）')
    return
  }
  submitting.value = true
  try {
    await shopApi.apply({
      shopName: form.value.shopName,
      shopLogo: form.value.shopLogo?.[0] || null,
      description: form.value.description,
      address: form.value.address,
      contactPhone: form.value.contactPhone,
      supportExpress: form.value.supportExpress,
      products: validProducts
    })
    ElMessage.success('申请已提交，等待管理员审核')
    router.replace('/my-shop')   // 提交后到「我的店铺」查看审核情况
  } catch (e) {
    // 拦截器已弹出错误提示；保留表单，便于用户修正后重新提交
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.prefill-alert { margin-bottom: 16px; }
.hint { color: #909399; font-size: 12px; margin-left: 10px; }
.p-card { border: 1px solid #ebeef5; border-radius: 6px; padding: 12px; margin-bottom: 12px; background: #fafafa; }
.p-head { display: flex; justify-content: space-between; margin-bottom: 8px; }
</style>
