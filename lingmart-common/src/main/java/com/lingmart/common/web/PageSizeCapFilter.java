package com.lingmart.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分页参数封顶过滤器：把请求里的 {@code page} / {@code size} 收敛到合法区间。
 *
 * <p><b>为什么需要它</b>：各列表接口的 {@code size} 直接来自客户端，原样传给
 * MyBatis-Plus 的 {@code Page} 对象。{@code ?size=1000000} 会把整张表拉进内存，
 * 既是内存打爆的入口，也是「一次请求换一次全表扫描」的放大器。搜索接口早已自行
 * 封顶（{@code min(size, 100)}），但订单、商品、店铺、聊天等列表都没有——
 * 逐个控制器加校验既容易漏，也会在新增接口时重新变回原样。</p>
 *
 * <p><b>为什么用过滤器而不是拦截器/参数解析器</b>：{@code @RequestParam} 的绑定值
 * 来自 {@link HttpServletRequest#getParameter}，在请求进入 Servlet 之前把参数
 * 改写掉，意味着所有现存与将来新增的 {@code size} 参数都自动受约束，控制器一行都不用改。
 * 参数名是唯一约定，与 Spring 的绑定实现无关。</p>
 *
 * <p><b>只处理查询串</b>：只有 URL 上出现 {@code page=} / {@code size=} 时才包装请求，
 * 从而完全不触碰 multipart 请求体与表单参数的解析路径（上传接口首当其冲）。</p>
 *
 * <p>非法值（非数字）原样放行，交由 Spring 报类型错误（400），此处不做静默纠正。</p>
 */
public class PageSizeCapFilter extends OncePerRequestFilter {

    /** 页码参数名 */
    public static final String PARAM_PAGE = "page";

    /** 每页条数参数名 */
    public static final String PARAM_SIZE = "size";

    /** 每页条数上限 */
    private final int maxSize;

    /** 页码上限（防 page*size 溢出与超大 OFFSET） */
    private final int maxPage;

    public PageSizeCapFilter(int maxSize, int maxPage) {
        this.maxSize = maxSize;
        this.maxPage = maxPage;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String query = request.getQueryString();
        if (query == null || !(query.contains(PARAM_PAGE + "=") || query.contains(PARAM_SIZE + "="))) {
            filterChain.doFilter(request, response);
            return;
        }
        filterChain.doFilter(new CappedRequest(request, maxSize, maxPage), response);
    }

    /** 只重写 page / size，其余参数原样透传。 */
    private static final class CappedRequest extends HttpServletRequestWrapper {

        private final int maxSize;
        private final int maxPage;

        private CappedRequest(HttpServletRequest request, int maxSize, int maxPage) {
            super(request);
            this.maxSize = maxSize;
            this.maxPage = maxPage;
        }

        @Override
        public String getParameter(String name) {
            return cap(name, super.getParameter(name));
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = super.getParameterValues(name);
            if (values == null || !isTarget(name)) {
                return values;
            }
            String[] capped = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                capped[i] = cap(name, values[i]);
            }
            return capped;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> source = super.getParameterMap();
            Map<String, String[]> result = new LinkedHashMap<>(source.size());
            source.forEach((key, values) -> result.put(key, isTarget(key) ? capAll(key, values) : values));
            return result;
        }

        private String[] capAll(String name, String[] values) {
            if (values == null) {
                return null;
            }
            String[] capped = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                capped[i] = cap(name, values[i]);
            }
            return capped;
        }

        /** 按参数名套用各自的上下限；非法数字原样返回。 */
        private String cap(String name, String raw) {
            if (raw == null || !isTarget(name)) {
                return raw;
            }
            long value;
            try {
                value = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                return raw;
            }
            long capped = PARAM_SIZE.equals(name)
                    ? Math.min(Math.max(value, 1), maxSize)
                    : Math.min(Math.max(value, 1), maxPage);
            return capped == value ? raw : String.valueOf(capped);
        }

        private static boolean isTarget(String name) {
            return PARAM_SIZE.equals(name) || PARAM_PAGE.equals(name);
        }
    }
}
