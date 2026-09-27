<template>
  <div class="card" style="max-width: 720px">
    <h3 class="page-title">申请开店</h3>
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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { shopApi } from '../api'
import ImageUploader from '../components/ImageUploader.vue'
import ProductEditor from '../components/ProductEditor.vue'

const router = useRouter()
const submitting = ref(false)
const form = ref({
  shopName: '',
  description: '',
  shopLogo: [],   // 单张 logo（上传组件返回数组，取第一张）
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
.p-card { border: 1px solid #ebeef5; border-radius: 6px; padding: 12px; margin-bottom: 12px; background: #fafafa; }
.p-head { display: flex; justify-content: space-between; margin-bottom: 8px; }
</style>
