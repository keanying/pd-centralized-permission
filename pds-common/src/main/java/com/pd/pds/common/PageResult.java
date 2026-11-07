package com.pd.pds.common;

import java.util.List;

/**
 * 分页结果。
 */
public record PageResult<T>(long total, long page, long pageSize, List<T> items) {
}
