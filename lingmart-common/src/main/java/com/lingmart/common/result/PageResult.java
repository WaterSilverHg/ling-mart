package com.lingmart.common.result;

import lombok.Data;

import java.util.List;

/**
 * 统一分页结果。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> list;

    /** 总条数 */
    private long total;

    /** 当前页码（从 1 起） */
    private long page;

    /** 每页大小 */
    private long size;

    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        PageResult<T> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }
}
