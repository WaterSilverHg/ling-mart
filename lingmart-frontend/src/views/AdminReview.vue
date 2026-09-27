<template>
  <div class="card">
    <h3 class="page-title">审核中心</h3>
    <el-tabs v-model="tab">
      <!-- 入驻申请审核 -->
      <el-tab-pane label="入驻申请" name="app">
        <ApplicationReview />
      </el-tab-pane>

      <!-- 商品审核（新商品 + 内容重审） -->
      <el-tab-pane label="商品审核" name="prod">
        <ProductReview />
      </el-tab-pane>

      <!-- 店铺信息变更审核 -->
      <el-tab-pane label="店铺信息" name="shop">
        <ShopInfoReview />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import ApplicationReview from '../components/admin/ApplicationReview.vue'
import ProductReview from '../components/admin/ProductReview.vue'
import ShopInfoReview from '../components/admin/ShopInfoReview.vue'

const tab = ref('app')
</script>
