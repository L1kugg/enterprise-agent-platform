package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalDatasetDeleteVO;
import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** deleteDataset 的联动删除语义：四层按序清理 + 未知数据集不动其它表。 */
class EvaluationServiceTest {

    private final EvalDatasetMapper datasetMapper = mock(EvalDatasetMapper.class);
    private final EvalCaseMapper caseMapper = mock(EvalCaseMapper.class);
    private final EvalRunMapper runMapper = mock(EvalRunMapper.class);
    private final EvalResultMapper resultMapper = mock(EvalResultMapper.class);

    private EvaluationService service() {
        return new EvaluationService(
                datasetMapper,
                caseMapper,
                runMapper,
                resultMapper,
                mock(HybridRagAnswerService.class),
                new ObjectMapper(),
                mock(EvaluationScorer.class),
                mock(EvaluationReportRenderer.class));
    }

    @Test
    void deleteDatasetCascadesAllFourLayers() {
        when(datasetMapper.findByTenantAndDatasetId("t1", "ds-1")).thenReturn(EvalDatasetRecord.builder()
                .datasetId("ds-1")
                .tenantId("t1")
                .name("冒烟集")
                .build());
        when(resultMapper.deleteByTenantAndDatasetId("t1", "ds-1")).thenReturn(5);
        when(runMapper.deleteByTenantAndDatasetId("t1", "ds-1")).thenReturn(2);
        when(caseMapper.deleteByTenantAndDatasetId("t1", "ds-1")).thenReturn(3);

        EvalDatasetDeleteVO vo = service().deleteDataset("t1", "ds-1");

        assertEquals("冒烟集", vo.getDatasetName());
        assertEquals(3, vo.getCases());
        assertEquals(2, vo.getRuns());
        assertEquals(5, vo.getResults());
        verify(datasetMapper).deleteByTenantAndDatasetId("t1", "ds-1");
    }

    @Test
    void deleteDatasetRejectsUnknownIdWithoutTouchingOtherTables() {
        when(datasetMapper.findByTenantAndDatasetId("t1", "ghost")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service().deleteDataset("t1", "ghost"));

        verifyNoInteractions(caseMapper, runMapper, resultMapper);
        verify(datasetMapper, never()).deleteByTenantAndDatasetId("t1", "ghost");
    }
}
