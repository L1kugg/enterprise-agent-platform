package com.enterprise.iqk.domain.vo;

import lombok.Data;

/** 注册 / 密码登录共用请求体。 */
@Data
public class AuthCredentialsVO {
    private String username;
    private String password;
}
