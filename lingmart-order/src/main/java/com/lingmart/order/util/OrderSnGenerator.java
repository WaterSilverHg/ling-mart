package com.lingmart.order.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单号生成器：时间戳（毫秒）+ 3 位随机数，教学项目够用。
 */
public final class OrderSnGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private OrderSnGenerator() {
    }

    public static String generate() {
        return LocalDateTime.now().format(FMT)
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}
