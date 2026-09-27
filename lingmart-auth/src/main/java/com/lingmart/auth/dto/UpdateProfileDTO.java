package com.lingmart.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新用户资料请求参数（昵称 / 头像）。
 *
 * <p>两字段均可选：仅更新非空字段。头像为上传接口返回的相对路径
 * （/uploads/xxx），由前端经网关静态路由展示。</p>
 */
@Data
public class UpdateProfileDTO {

    /** 昵称（可选，更新时非空才覆盖） */
    @Size(max = 50, message = "昵称最长 50 个字符")
    private String nickname;

    /** 头像地址（可选，更新时非空才覆盖） */
    @Size(max = 255, message = "头像地址过长")
    private String avatar;
}
