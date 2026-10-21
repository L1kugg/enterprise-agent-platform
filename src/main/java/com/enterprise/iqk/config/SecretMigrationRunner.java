package com.enterprise.iqk.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 凭据存量迁移（FR-R12-01）：应用启动后扫描 platform_asset 全表，
 * 把 config_json 中命中敏感字段名且尚未加密（无 ENC1 信封）的值就地加密。
 * 幂等：已加密值被 SecretCipher 跳过；主密钥未配置时整体跳过并告警（保持明文兼容读）。
 * 注意：迁移执行后，回滚到不支持解密的旧版本将导致凭据不可读——发布说明需明示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretMigrationRunner implements ApplicationRunner {

    private final PlatformAssetMapper assetMapper;
    private final SecretCipher secretCipher;

    @Override
    public void run(ApplicationArguments args) {
        if (!secretCipher.available()) {
            log.error("APP_SECRET_KEY 未配置，跳过凭据存量迁移：当前凭据保持明文存储（FR-R12-01 未生效）");
            return;
        }
        try {
            List<PlatformAsset> assets = assetMapper.selectList(
                    new LambdaQueryWrapper<PlatformAsset>().isNotNull(PlatformAsset::getConfigJson));
            int migratedRows = 0;
            for (PlatformAsset asset : assets) {
                if (!secretCipher.containsPlainSecret(asset.getConfigJson())) {
                    continue;
                }
                String encrypted = secretCipher.transformFields(asset.getConfigJson(), true);
                PlatformAsset update = new PlatformAsset();
                update.setId(asset.getId());
                update.setConfigJson(encrypted);
                update.setUpdatedAt(LocalDateTime.now());
                assetMapper.updateById(update);
                migratedRows++;
            }
            if (migratedRows > 0) {
                log.info("凭据存量迁移完成：加密 {} 个资产的敏感字段", migratedRows);
            } else {
                log.info("凭据存量迁移：无需迁移（全部已加密或无敏感字段）");
            }
        } catch (Exception ex) {
            // 迁移失败不阻断启动：明文存量仍可读（解密器兼容明文），下次启动重试
            log.error("凭据存量迁移失败（下次启动重试）: reason={}", ex.toString());
        }
    }
}
