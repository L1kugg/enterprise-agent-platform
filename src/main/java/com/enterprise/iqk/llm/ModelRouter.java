package com.enterprise.iqk.llm;

import com.enterprise.iqk.config.properties.ModelRouterProperties;
import com.enterprise.iqk.domain.ModelAbExposure;
import com.enterprise.iqk.mapper.ModelAbExposureMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模型路由器：把"请求档位 + 端点场景 + 租户"解析为具体模型与成本档（economy/balanced/quality）。
 * 路由顺序：quality/cost 实验分桶 → 初始档位（显式请求 > 端点映射 > 默认档）
 * → 沿 fallback 链找启用档 → 兜底默认模型；
 * 命中实验时同步落曝光记录（尽力而为，绝不阻断路由主流程）。
 */
@Service
public class ModelRouter {
    /** 全链路兜底模型，所有回退路径的最终出口 */
    private static final String DEFAULT_MODEL = "qwen-plus";

    private final ModelRouterProperties modelRouterProperties;
    private final ModelAbExposureMapper modelAbExposureMapper;

    /** 构造注入；曝光 Mapper 经 ObjectProvider 懒取，缺库环境容忍为 null（仅失去曝光记录）。 */
    public ModelRouter(ModelRouterProperties modelRouterProperties,
                       ObjectProvider<ModelAbExposureMapper> modelAbExposureMapperProvider) {
        this.modelRouterProperties = modelRouterProperties;
        this.modelAbExposureMapper = initMapper(modelAbExposureMapperProvider);
    }

    /** 懒取 Mapper，任何异常降级为 null。 */
    private static ModelAbExposureMapper initMapper(
            ObjectProvider<ModelAbExposureMapper> provider) {
        try {
            return provider.getIfAvailable();
        } catch (Exception ex) {
            return null;
        }
    }

    /** 简化重载：按公共租户、空主体键路由。 */
    public ModelRouteDecision resolve(String requestedProfile, String endpoint) {
        return resolve(requestedProfile, endpoint, "public", "");
    }

    /** 主入口：实验分桶 → 初始档位 → 启用/回退链解析，返回路由决策并按需落实验曝光。 */
    public ModelRouteDecision resolve(String requestedProfile,
                                      String endpoint,
                                      String tenantId,
                                      String subjectKey) {
        String normalizedTenant = normalizeTenant(tenantId);
        ExperimentRouting experiment = applyExperiment(requestedProfile, endpoint, normalizedTenant, subjectKey);
        String initialProfile = normalizeProfile(selectInitialProfile(experiment.routedProfile(), endpoint));

        ModelRouteDecision baseDecision;
        if (!modelRouterProperties.isEnabled()) {
            baseDecision = resolveDisabled(initialProfile);
        } else {
            baseDecision = resolveEnabled(initialProfile);
        }

        ModelRouteDecision decision = new ModelRouteDecision(
                baseDecision.profile(),
                baseDecision.model(),
                baseDecision.costTier(),
                baseDecision.fallbackApplied(),
                baseDecision.reason(),
                experiment.experimentKey(),
                experiment.variant(),
                experiment.bucket()
        );
        saveExposure(normalizedTenant, endpoint, subjectKey, decision);
        return decision;
    }

    /** 落库实验曝光记录；无 Mapper、非实验流量或写库失败均静默跳过。 */
    private void saveExposure(String tenantId, String endpoint, String subjectKey, ModelRouteDecision decision) {
        if (modelAbExposureMapper == null || !StringUtils.hasText(decision.experimentKey())) {
            return;
        }
        try {
            modelAbExposureMapper.insert(ModelAbExposure.builder()
                    .tenantId(normalizeTenant(tenantId))
                    .experimentKey(decision.experimentKey())
                    .subjectKey(StringUtils.hasText(subjectKey) ? subjectKey : "na")
                    .endpoint(StringUtils.hasText(endpoint) ? endpoint : "unknown")
                    .bucket(decision.experimentBucket() != null ? decision.experimentBucket() : -1)
                    .variant(StringUtils.hasText(decision.experimentVariant()) ? decision.experimentVariant() : "unknown")
                    .routedProfile(decision.profile())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception ignored) {
            // 曝光日志记录绝不能阻断路由主流程
        }
    }

    /** quality/cost 实验分桶：手动指定档位直通；命中触发档按 tenant+endpoint+subject 哈希定桶，未启用则原样返回。 */
    private ExperimentRouting applyExperiment(String requestedProfile,
                                              String endpoint,
                                              String tenantId,
                                              String subjectKey) {
        String normalizedRequested = normalizeProfile(requestedProfile);
        if ("quality_first".equals(normalizedRequested) || "quality-priority".equals(normalizedRequested)) {
            return new ExperimentRouting("quality", "manual_quality_first", "quality", 100);
        }
        if ("cost_first".equals(normalizedRequested) || "cost-priority".equals(normalizedRequested)) {
            return new ExperimentRouting("economy", "manual_cost_first", "cost", 0);
        }

        ModelRouterProperties.AbExperiment experiment = modelRouterProperties.getQualityCostExperiment();
        String triggerProfile = normalizeProfile(experiment.getTriggerProfile());
        boolean triggerMatched = StringUtils.hasText(triggerProfile) && triggerProfile.equals(normalizedRequested);
        if (!experiment.isEnabled() || !triggerMatched) {
            return new ExperimentRouting(normalizedRequested, "", "", null);
        }

        int qualityPercent = Math.min(100, Math.max(0, experiment.getQualityPercent()));
        String normalizedEndpoint = StringUtils.hasText(endpoint) ? normalizeProfile(endpoint) : "na";
        String normalizedSubject = StringUtils.hasText(subjectKey) ? subjectKey.trim() : "na";
        int rawHash = (tenantId + "|" + normalizedEndpoint + "|" + normalizedSubject).hashCode();
        int bucket = Math.floorMod(rawHash, 100);
        boolean qualityVariant = bucket < qualityPercent;
        String variant = qualityVariant ? "quality" : "cost";
        String routedProfile = qualityVariant ? "quality" : "economy";
        return new ExperimentRouting(
                routedProfile,
                StringUtils.hasText(experiment.getExperimentKey()) ? experiment.getExperimentKey() : "quality_vs_cost",
                variant,
                bucket
        );
    }

    /** 路由总开关关闭时：档位配置齐全直接取该档模型，否则默认模型。 */
    private ModelRouteDecision resolveDisabled(String initialProfile) {
        if (hasRoute(initialProfile)) {
            ModelRouterProperties.RouteProfile profile = modelRouterProperties.getProfiles().get(initialProfile);
            return new ModelRouteDecision(initialProfile,
                    profile.getModel(),
                    safeCostTier(profile.getCostTier()),
                    false,
                    "router_disabled_profile_direct",
                    "",
                    "",
                    null);
        }
        return new ModelRouteDecision(initialProfile, DEFAULT_MODEL, "balanced", false, "router_disabled_default", "", "", null);
    }

    /** 沿 fallback 链找第一个启用且有模型的档位（防环）；链断则取任一启用档，仍无则兜底默认模型。 */
    private ModelRouteDecision resolveEnabled(String initialProfile) {
        Set<String> visited = new LinkedHashSet<>();
        String current = initialProfile;
        while (StringUtils.hasText(current) && !visited.contains(current)) {
            visited.add(current);
            ModelRouterProperties.RouteProfile route = modelRouterProperties.getProfiles().get(current);
            if (route != null && route.isEnabled() && StringUtils.hasText(route.getModel())) {
                return new ModelRouteDecision(current,
                        route.getModel(),
                        safeCostTier(route.getCostTier()),
                        !current.equals(initialProfile),
                        !current.equals(initialProfile) ? "fallback_chain" : "profile_match",
                        "",
                        "",
                        null);
            }
            String fallback = route == null ? "" : normalizeProfile(route.getFallbackProfile());
            if (!StringUtils.hasText(fallback)) {
                fallback = normalizeProfile(modelRouterProperties.getDefaultProfile());
            }
            if (!StringUtils.hasText(fallback) || visited.contains(fallback)) {
                break;
            }
            current = fallback;
        }
        for (Map.Entry<String, ModelRouterProperties.RouteProfile> entry : modelRouterProperties.getProfiles().entrySet()) {
            ModelRouterProperties.RouteProfile route = entry.getValue();
            if (route != null && route.isEnabled() && StringUtils.hasText(route.getModel())) {
                return new ModelRouteDecision(entry.getKey(),
                        route.getModel(),
                        safeCostTier(route.getCostTier()),
                        !entry.getKey().equals(initialProfile),
                        "first_enabled_fallback",
                        "",
                        "",
                        null);
            }
        }
        return new ModelRouteDecision(initialProfile, DEFAULT_MODEL, "balanced", true, "default_model_fallback", "", "", null);
    }

    /** 初始档位选择：显式请求 > 端点场景映射 > 默认档位。 */
    private String selectInitialProfile(String requestedProfile, String endpoint) {
        if (StringUtils.hasText(requestedProfile)) {
            return requestedProfile;
        }
        if (StringUtils.hasText(endpoint)) {
            String endpointProfile = modelRouterProperties.getEndpointProfiles().get(normalizeProfile(endpoint));
            if (StringUtils.hasText(endpointProfile)) {
                return endpointProfile;
            }
        }
        return modelRouterProperties.getDefaultProfile();
    }

    /** 档位是否存在且配置了模型。 */
    private boolean hasRoute(String profile) {
        return StringUtils.hasText(profile)
                && modelRouterProperties.getProfiles().containsKey(profile)
                && StringUtils.hasText(modelRouterProperties.getProfiles().get(profile).getModel());
    }

    /** 档位名归一：trim + 小写，空白返回空串。 */
    private String normalizeProfile(String profile) {
        if (!StringUtils.hasText(profile)) {
            return "";
        }
        return profile.trim().toLowerCase(Locale.ROOT);
    }

    /** 成本档缺省补 balanced。 */
    private String safeCostTier(String costTier) {
        return StringUtils.hasText(costTier) ? costTier : "balanced";
    }

    /** 租户归一：空白归为 public。 */
    private String normalizeTenant(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return "public";
        }
        return tenantId.trim();
    }

    /** 路由决策：最终档位/模型/成本档 + 是否触发回退 + 原因 + 实验键/变体/桶号。 */
    public record ModelRouteDecision(String profile,
                                     String model,
                                     String costTier,
                                     boolean fallbackApplied,
                                     String reason,
                                     String experimentKey,
                                     String experimentVariant,
                                     Integer experimentBucket) {
    }

    /** 实验分桶内部结果：实际路由档位 + 实验键/变体/桶号。 */
    private record ExperimentRouting(String routedProfile,
                                     String experimentKey,
                                     String variant,
                                     Integer bucket) {
    }
}
