package com.lingmart.order.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmart.order.entity.Order;
import com.lingmart.order.entity.OrderItem;
import com.lingmart.order.entity.UserAddress;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 测试期初始化 MyBatis-Plus 的 Lambda 缓存（{@code SFunction} → 列名）。
 *
 * <p><b>为什么需要</b>：order 模块的单元测试是纯 Mockito，没有 Spring / MyBatis 上下文，
 * 也没有 Mapper 扫描，因此 {@link TableInfoHelper} 的缓存是空的。当被测代码里出现
 * {@code LambdaUpdateWrapper#set(column, null)} 这类**需要立即解析列名**的写法时
 * （订单状态守卫清空字段就用了它），会抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}。</p>
 *
 * <p><b>为什么抽成公共类</b>：原先只有 {@code OrderFlowIntegrationTest} 在自己的
 * {@code @BeforeAll} 里初始化 {@code Order}，于是 {@code OrderServiceImplTest} 是否通过
 * 取决于**测试类的执行顺序**——本地恰好先跑集成测试，缓存已被填充，全绿；
 * CI（Linux、JUnit 的类顺序不同）先跑单测，就直接失败。这是典型的测试相互污染。</p>
 *
 * <p>现在每个需要它的测试类都在自己的 {@code @BeforeAll} 里调用一次，
 * 无论谁先执行都能正常工作。方法可重复调用（{@code initTableInfo} 会重建元信息）。</p>
 */
public final class MybatisLambdaCache {

    private MybatisLambdaCache() {
    }

    /**
     * 初始化 order 模块所有实体的 Lambda 缓存。
     *
     * <p>目前覆盖：{@link Order}（状态守卫）、{@link OrderItem}（明细批量查询）、
     * {@link UserAddress}（地址默认标记）。新增实体时记得在这里补一行。</p>
     */
    public static void initOrderModule() {
        init(Order.class);
        init(OrderItem.class);
        init(UserAddress.class);
    }

    /**
     * 初始化单个实体的 Lambda 缓存。
     *
     * <p>每个实体单独创建一个 {@link MapperBuilderAssistant}：它内部会累积命名空间状态，
     * 多个实体共用同一个实例容易踩到表格元信息的干扰。</p>
     *
     * @param entityClass 实体类
     */
    private static void init(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
