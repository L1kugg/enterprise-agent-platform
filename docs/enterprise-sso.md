# 企业 SSO 接入指南

> 状态：📋 规划中 — 本文档描述目标接入模式。

## 支持的身份提供商

KnowledgeOps Agent 通过 OpenID Connect（OIDC）与 SAML 2.0 支持企业 SSO。

## 配置

```yaml
app:
  security:
    sso:
      enabled: ${APP_SSO_ENABLED:false}
      provider: ${APP_SSO_PROVIDER:oidc}   # oidc | saml
      # OIDC
      oidc:
        issuer-uri: ${APP_SSO_OIDC_ISSUER:}
        client-id: ${APP_SSO_OIDC_CLIENT_ID:}
        client-secret: ${APP_SSO_OIDC_CLIENT_SECRET:}
      # SAML
      saml:
        metadata-url: ${APP_SSO_SAML_METADATA_URL:}
        entity-id: ${APP_SSO_SAML_ENTITY_ID:}
```

## 用户开通

启用 SSO 后：
1. 用户通过企业 IdP 完成认证
2. OIDC/SAML 令牌映射为 KnowledgeOps 的租户与角色
3. 签发携带租户级 claims 的 JWT
4. 现有 API Key 认证对服务账号继续可用

## 路线图

- [ ] 基于 `spring-boot-starter-oauth2-client` 实现 OIDC Relying Party
- [ ] 将 IdP 用户组映射为 KnowledgeOps RBAC 角色
- [ ] 增加 SAML SP metadata 端点
- [ ] 补充 Azure AD 与 Okta 快速接入配置文档
