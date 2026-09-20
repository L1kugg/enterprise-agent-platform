package com.enterprise.iqk.tools;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.enterprise.iqk.domain.Course;
import com.enterprise.iqk.domain.CourseReservation;
import com.enterprise.iqk.domain.School;
import com.enterprise.iqk.domain.query.CourseQuery;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.ICourseReservationService;
import com.enterprise.iqk.service.ICourseService;
import com.enterprise.iqk.service.ISchoolService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
/**
 * 课程领域工具集：暴露给 agent 的三个 @Tool 入口
 * （query_school / query_course / add_course_reservation）。
 * 所有操作从 TenantContext 取租户做行级过滤或打标；课程排序走字段白名单 +
 * SFunction 映射，ORDER BY 永不拼接客户端原始字符串；调用统一埋点 tool.query.latency。
 */
public class CourseTools {
    /** 允许参与 ORDER BY 的排序字段白名单，白名单外的排序条件直接忽略 */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("price", "duration", "edu", "id");

    private final ICourseService courseService;
    private final ISchoolService schoolService;
    private final ICourseReservationService courseReservationService;
    private final MeterRegistry meterRegistry;

    @Tool(description = "根据条件查询对应的课程，返回的是课程的列表集合")
    /** 按条件查询课程列表：强制租户过滤 + 动态拼接 edu/type 条件 + 白名单排序。 */
    public List<Course> queryCourse(@ToolParam(required = false, description = "需要查询的课程的条件") CourseQuery query) {
        return instrument("query_course", () -> {
            CourseQuery safeQuery = query == null ? new CourseQuery() : query;
            // 租户隔离：V17 给课程表加了 tenant_id；
            // @Tool 方法签名一旦改动就会破坏 agent 契约，
            // 所以这里从 TenantContext（由认证过滤器在请求线程上设置）
            // 取出租户并在此处应用过滤条件。
            String tenantId = TenantContext.currentTenantId();
            LambdaQueryWrapper<Course> qw = new LambdaQueryWrapper<>();
            qw.eq(Course::getTenantId, tenantId);
            if (safeQuery.getEdu() != null) {
                qw.le(Course::getEdu, safeQuery.getEdu());
            }
            if (StrUtil.isNotBlank(safeQuery.getType())) {
                qw.eq(Course::getType, safeQuery.getType());
            }

            if (CollUtil.isNotEmpty(safeQuery.getSorts())) {
                for (CourseQuery.Sort sort : safeQuery.getSorts()) {
                    if (sort == null || !ALLOWED_SORT_FIELDS.contains(sort.getField())) {
                        continue;
                    }
                    boolean isAsc = sort.getIsAsc() == null || sort.getIsAsc();
                    qw.orderBy(true, isAsc, getSortColumn(sort.getField()));
                }
            }
            return courseService.list(qw);
        });
    }

    @Tool(description = "查询所有的校区列表")
    /** 查询当前租户的全部校区列表。 */
    public List<School> querySchool() {
        return instrument("query_school", () -> {
            String tenantId = TenantContext.currentTenantId();
            return schoolService.list(
                    new QueryWrapper<School>().eq("tenant_id", tenantId)
            );
        });
    }

    @Tool(description = "新增学生的预约单记录，并且返回预约的单号")
    /** 新增学生预约单并返回单号；记录自动打上当前租户标记，实现跨租户读写隔离。 */
    public String addCourseReservation(
            @ToolParam(required = true, description = "学生预约的课程名称") String course,
            @ToolParam(required = true, description = "学生预留的姓名") String studentName,
            @ToolParam(required = true, description = "学生预留的联系方式") String contactInfo,
            @ToolParam(required = true, description = "学生选择试听的校区名字") String school,
            @ToolParam(required = false, description = "学生预留的备注信息") String remark
    ) {
        return instrument("add_course_reservation", () -> {
            // 给预约记录打上调用方租户的标记，使其他租户
            // 无法通过 listByMap 或管理工具读取或统计到该行。
            String tenantId = TenantContext.currentTenantId();
            CourseReservation reservation = new CourseReservation();
            reservation.setTenantId(tenantId);
            reservation.setCourse(course);
            reservation.setStudentName(studentName);
            reservation.setContactInfo(contactInfo);
            reservation.setSchool(school);
            reservation.setRemark(remark);
            courseReservationService.save(reservation);
            return reservation.getId().toString();
        });
    }

    /**
     * 将白名单内的排序字段映射为类型安全的列引用，
     * 确保 ORDER BY 子句永远不会由客户端原始字符串拼出。
     */
    private SFunction<Course, ?> getSortColumn(String field) {
        return switch (field) {
            case "price" -> Course::getPrice;
            case "duration" -> Course::getDuration;
            case "edu" -> Course::getEdu;
            case "id" -> Course::getId;
            default -> Course::getId;
        };
    }

    /** 为工具调用包一层成功/失败计时的埋点，异常原样上抛。 */
    private <T> T instrument(String toolName, Supplier<T> operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = operation.get();
            stopTimer(sample, toolName, "success");
            return result;
        } catch (RuntimeException ex) {
            stopTimer(sample, toolName, "error");
            throw ex;
        }
    }

    /** 结束计时样本并注册 tool.query.latency 指标（带 tool/status 标签）。 */
    private void stopTimer(Timer.Sample sample, String toolName, String status) {
        sample.stop(Timer.builder("tool.query.latency")
                .description("Latency for tool-layer query and write operations")
                .tag("tool", toolName)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }
}
