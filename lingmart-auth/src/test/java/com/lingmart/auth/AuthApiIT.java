package com.lingmart.auth;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 认证中心接口集成测试（REST Assured）。
 *
 * <p>对已运行的 auth 服务（localhost:8000）发起真实 HTTP 请求，覆盖
 * 「发送验证码 → 注册 → 登录 → 携带 Token 查询用户 → 退出」全链路，
 * 以及未登录拦截、错误验证码等异常分支。</p>
 *
 * <p>约定：本类以 {@code *IT} 结尾，surefire 默认不会在 {@code mvn test} 中执行，
 * 需在 IDEA 直接运行，或执行 {@code mvn test -Dtest=AuthApiIT}。
 * 服务未启动时自动跳过，不影响普通构建。</p>
 */
@Tag("integration")
class AuthApiIT {

    /** auth 服务地址（经网关为 localhost:88/api/auth） */
    private static final String BASE_URL = "http://localhost:8000/api/auth";

    @BeforeAll
    static void requireRunningService() {
        Assumptions.assumeTrue(isServiceUp(), "认证服务未运行（localhost:8000），跳过集成测试");
        RestAssured.baseURI = BASE_URL;
    }

    @Test
    void shouldCompleteAuthFlow() {
        String phone = uniquePhone();

        // 1. 发送验证码（仅 DEV_CODE_ENABLED=true 时返回明文 devCode，便于联调）
        String devCode = given().queryParam("phone", phone)
                .post("/code/send")
                .then().statusCode(200)
                .body("code", equalTo(200))
                .extract().jsonPath().getString("data.devCode");
        // 生产模式（未开启 dev 开关）不返回明文，注册/登录联调无法进行，跳过
        Assumptions.assumeTrue(devCode != null,
                "auth 未开启 DEV_CODE_ENABLED（默认 false），跳过注册/登录联调");

        // 2. 注册（使用接口返回的验证码）
        given().contentType(ContentType.JSON)
                .body(Map.of("phone", phone, "code", devCode,
                        "password", "pass123456", "nickname", "集成测试用户"))
                .post("/register")
                .then().statusCode(200)
                .body("code", equalTo(200));

        // 3. 登录获取 JWT
        String token = given().contentType(ContentType.JSON)
                .body(Map.of("phone", phone, "password", "pass123456"))
                .post("/login")
                .then().statusCode(200)
                .body("code", equalTo(200))
                .body("data.token", notNullValue())
                .extract().jsonPath().getString("data.token");
        assertNotNull(token);

        // 4. 携带 Token 查询当前用户
        given().header("Authorization", "Bearer " + token)
                .get("/user/me")
                .then().statusCode(200)
                .body("code", equalTo(200))
                .body("data.phone", equalTo(phone));

        // 5. 退出登录（无状态 JWT，仅验证接口可用）
        given().header("Authorization", "Bearer " + token)
                .post("/logout")
                .then().statusCode(200)
                .body("code", equalTo(200));
    }

    @Test
    void shouldRejectMeWithoutToken() {
        // 未携带 Token 访问受保护接口（直连 auth，网关未介入），应返回 401
        given().get("/user/me")
                .then().statusCode(200)
                .body("code", equalTo(401));
    }

    @Test
    void shouldRejectRegisterWithWrongCode() {
        // 错误验证码注册应被业务层拦截（默认业务错误码 60000）
        given().contentType(ContentType.JSON)
                .body(Map.of("phone", uniquePhone(), "code", "000000",
                        "password", "pass123456", "nickname", "集成测试用户"))
                .post("/register")
                .then().statusCode(200)
                .body("code", equalTo(60000));
    }

    /** 生成不重复的 11 位手机号：139 + 8 位随机数 */
    private static String uniquePhone() {
        return "139" + String.format("%08d", System.nanoTime() % 100_000_000L);
    }

    /** 探测 auth 服务是否可访问 */
    private static boolean isServiceUp() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:8000/api/auth/code/send?phone=13900000000").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
