package com.lingmart.shop.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.common.util.RedisRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 图片上传接口（本地磁盘存储）。
 *
 * <p>统一图片上传入口：开店商品图 / 店铺 logo / 商品主图 / 用户头像均经本接口上传，
 * 返回可访问的相对路径（{@code /uploads/yyyyMMdd/uuid.ext}），由网关静态路由对外提供。
 * 文件落在 {@code lingmart.upload.dir}（默认 ./uploads），生产可经环境变量 {@code UPLOAD_DIR} 覆盖，
 * 后续接 OSS 时仅需替换存储实现，接口契约不变。</p>
 *
 * <p>安全：白名单扩展名 + 文件头魔数双重校验（防伪装成图片的恶意文件）、单文件 ≤5MB；
 * 需登录（网关 JWT 鉴权）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/shop/upload")
@RequiredArgsConstructor
public class UploadController {

    /** 单文件大小上限：5MB */
    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    /** 上传频控：每用户每分钟最多上传张数（防刷屏/灌满磁盘） */
    private static final int UPLOAD_MAX_PER_MINUTE = 20;

    /** 图片类型白名单 → 扩展名（按 Content-Type 放行，避免伪装文件） */
    private static final Map<String, String> IMAGE_EXT = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif",
            "image/bmp", ".bmp"
    );

    /** 上传根目录（生产用 UPLOAD_DIR 覆盖） */
    @Value("${lingmart.upload.dir:./uploads}")
    private String uploadDir;

    /** 上传频控器（Redis 分布式滑动窗口，多实例共享，防恶意灌盘） */
    private final RedisRateLimiter uploadLimiter;

    /**
     * 上传图片。
     *
     * @param file   图片文件（multipart，字段名 file）
     * @param userId 当前用户 ID（网关透传，用于频控）
     * @return 可访问的相对路径，如 /uploads/20260819/ab12cd....jpg
     */
    @PostMapping("/image")
    public R<String> uploadImage(@RequestParam("file") MultipartFile file,
                                 @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException("图片大小不能超过 5MB");
        }
        // 每用户频控：1 分钟内最多 UPLOAD_MAX_PER_MINUTE 张，防恶意灌盘
        if (userId != null && !uploadLimiter.allow("upload:" + userId, UPLOAD_MAX_PER_MINUTE, 60_000L)) {
            throw new BizException("上传过于频繁，请稍后再试");
        }
        String ext = detectExt(file);
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        String relativePath = dateDir + "/" + filename;

        // 目标目录用绝对路径：规避 Windows 下 transferTo 的相对路径被 Tomcat 按 multipart
        // location 解析、以及 renameTo 跨目录失败导致 IOException 的坑（静态资源仍按 /uploads/** 相对访问）
        File dir = new File(new File(uploadDir).getAbsoluteFile(), dateDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BizException("上传目录创建失败，请联系管理员");
        }
        File target = new File(dir, filename);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("图片保存失败：absolutePath={}, err={}", target.getAbsolutePath(), e.getMessage());
            throw new BizException("图片保存失败，请稍后再试");
        }

        String url = "/uploads/" + relativePath;
        log.info("图片上传成功：url={}, size={}, userId={}", url, file.getSize(), userId);
        return R.ok(url);
    }

    /** 读取文件头用于魔数判定的最小字节数（WEBP 需要 12 字节：RIFF + 4 字节长度 + WEBP） */
    private static final int MAGIC_READ_LEN = 12;

    /**
     * 校验图片类型并返回扩展名：Content-Type 白名单前置校验 + 文件头魔数双重校验。
     *
     * <p>仅依赖 Content-Type 可被伪装（恶意文件声明为 image/jpeg 即可通过），
     * 故必须以文件真实头部字节二次确认，魔数无法识别或与声明类型不一致时直接拒绝。</p>
     *
     * @param file 上传文件
     * @return 图片扩展名（含点前缀，如 .jpg）
     */
    private String detectExt(MultipartFile file) {
        // 1) Content-Type 白名单前置校验（快速失败）
        String contentType = file.getContentType();
        String contentTypeExt = contentType == null ? null : IMAGE_EXT.get(contentType);
        if (contentTypeExt == null) {
            throw new BizException("不支持的图片格式（支持 jpg/png/webp/gif/bmp）");
        }
        // 2) 读取文件头魔数，确认真实文件类型
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(MAGIC_READ_LEN);
        } catch (IOException e) {
            log.warn("读取图片文件头失败：err={}", e.getMessage());
            throw new BizException("图片读取失败，请重试");
        }
        String magicExt = detectMagicExt(header);
        if (magicExt == null) {
            throw new BizException("文件内容与图片格式不符，请更换文件");
        }
        // 3) Content-Type 与魔数必须一致，双保险
        if (!contentTypeExt.equals(magicExt)) {
            throw new BizException("文件类型与声明不一致，请更换文件");
        }
        return magicExt;
    }

    /**
     * 依据文件头魔数识别图片类型。
     *
     * @param b 文件头字节（可能不足 {@link #MAGIC_READ_LEN}，按实际长度判断）
     * @return 匹配的扩展名；无法识别返回 null
     */
    private String detectMagicExt(byte[] b) {
        // JPEG：FF D8 FF
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        // PNG：89 50 4E 47 0D 0A 1A 0A
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return ".png";
        }
        // GIF：'GIF8'（GIF87a / GIF89a）
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return ".gif";
        }
        // BMP：'BM'
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') {
            return ".bmp";
        }
        // WEBP：'RIFF' + 4 字节长度 + 'WEBP'
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return ".webp";
        }
        return null;
    }
}
