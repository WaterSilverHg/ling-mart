<template>
  <div class="card">
    <h3 class="page-title">购物车</h3>
    <el-empty v-if="list.length === 0" description="购物车是空的，去逛逛吧">
      <el-button type="primary" @click="$router.push('/')">去首页逛逛</el-button>
    </el-empty>
    <template v-else>
      <el-table :data="list">
        <el-table-column label="商品" min-width="260">
          <template #default="{ row }">
            <div class="goods-cell" title="点击查看商品详情" @click="goProduct(row)">
              <img v-img-fallback :src="row.skuImage || placeholder" class="thumb" />
              <div>
                <div class="spu">{{ row.spuTitle }}</div>
                <div class="sku">{{ row.skuTitle }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="120">
          <template #default="{ row }">¥{{ row.price }}</template>
        </el-table-column>
        <el-table-column label="数量" width="180">
          <template #default="{ row }">
            <el-input-number :model-value="row.num" :min="1" size="small" @change="(v) => updateNum(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="小计" width="120">
          <template #default="{ row }">
            <span class="price">¥{{ (row.price * row.num).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="footer">
        <span>共 <b>{{ list.length }}</b> 件商品</span>
        <div>
          <el-button type="primary" @click="checkout">去结算</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cartApi } from '../api'

const router = useRouter()
const list = ref([])
const placeholder = 'https://via.placeholder.com/60x60/eee?text='

async function load() {
  list.value = await cartApi.list()
}

async function updateNum(row, v) {
  await cartApi.update(row.id, { num: v })
  row.num = v
}

async function remove(row) {
  await ElMessageBox.confirm('确定删除该商品吗？', '提示', { type: 'warning' })
  await cartApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

/** 点击商品跳详情页。详情页路由参数是 spuId（见 router 的 /product/:id） */
function goProduct(row) {
  if (!row.spuId) {
    return
  }
  router.push(`/product/${row.spuId}`)
}

function checkout() {
  // 携带购物车快照（含价格），结算页才能正确显示金额；实际下单金额仍以后端服务端快照为准
  const items = list.value.map(i => ({
    skuId: i.skuId,
    num: i.num,
    spuTitle: i.spuTitle,
    skuTitle: i.skuTitle,
    skuImage: i.skuImage,
    price: i.price
  }))
  if (items.length === 0) {
    ElMessage.warning('购物车为空')
    return
  }
  const encoded = encodeURIComponent(JSON.stringify(items))
  router.push({ path: '/checkout', query: { items: encoded, fromCart: '1' } })
}

onMounted(load)
</script>

<style scoped>
.goods-cell { display: flex; gap: 10px; align-items: center; cursor: pointer; }
.goods-cell:hover .spu { color: #409eff; }
.thumb { width: 60px; height: 60px; object-fit: cover; border-radius: 4px; background: #f0f2f5; }
.spu { font-size: 14px; transition: color .2s; }
.sku { color: #909399; font-size: 12px; }
.price { color: #f56c6c; font-weight: 600; }
.footer { display: flex; justify-content: flex-end; align-items: center; gap: 24px; margin-top: 16px; }
</style>
