package com.lingmart.ware.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.ware.entity.WareSku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 库存 Mapper：含并发安全的条件更新（原子扣减）。
 */
public interface WareSkuMapper extends BaseMapper<WareSku> {

    /**
     * 原子锁定库存：仅当可售余量充足时执行。
     *
     * <p>{@code WHERE stock - locked_stock >= #{num}} 在行锁内校验余量，影响行数为 0
     * 说明库存不足，由调用方抛业务异常触发 Seata 全局回滚。</p>
     *
     * @param skuId  SKU ID
     * @param shopId 店铺ID
     * @param num    锁定数量
     * @return 影响行数（0 表示库存不足）
     */
    @Update("UPDATE ware_sku SET locked_stock = locked_stock + #{num} " +
            "WHERE sku_id = #{skuId} AND shop_id = #{shopId} AND stock - locked_stock >= #{num}")
    int tryLockStock(@Param("skuId") Long skuId, @Param("shopId") Long shopId, @Param("num") Integer num);

    /**
     * 解锁库存：取消/超时取消时释放锁定占用。
     *
     * @param skuId  SKU ID
     * @param shopId 店铺ID
     * @param num    解锁数量
     * @return 影响行数
     */
    @Update("UPDATE ware_sku SET locked_stock = locked_stock - #{num} " +
            "WHERE sku_id = #{skuId} AND shop_id = #{shopId} AND locked_stock >= #{num}")
    int unlockStock(@Param("skuId") Long skuId, @Param("shopId") Long shopId, @Param("num") Integer num);

    /**
     * 扣减库存：支付成功后，锁定占用转为真实售出（可售与锁定各减 num）。
     *
     * @param skuId  SKU ID
     * @param shopId 店铺ID
     * @param num    扣减数量
     * @return 影响行数
     */
    @Update("UPDATE ware_sku SET locked_stock = locked_stock - #{num}, stock = stock - #{num} " +
            "WHERE sku_id = #{skuId} AND shop_id = #{shopId} AND locked_stock >= #{num}")
    int deductStock(@Param("skuId") Long skuId, @Param("shopId") Long shopId, @Param("num") Integer num);

    /**
     * 回补库存：退款通过后，将已售出商品重新加回可售库存（仅加可售，不改锁定）。
     *
     * @param skuId  SKU ID
     * @param shopId 店铺ID
     * @param num    回补数量
     * @return 影响行数（0 表示 SKU 不存在，由调用方抛异常触发全局回滚）
     */
    @Update("UPDATE ware_sku SET stock = stock + #{num} " +
            "WHERE sku_id = #{skuId} AND shop_id = #{shopId}")
    int restockStock(@Param("skuId") Long skuId, @Param("shopId") Long shopId, @Param("num") Integer num);
}
