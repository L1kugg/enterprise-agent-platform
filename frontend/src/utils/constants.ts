// 全局常量：从 App.vue 单体拆出，值与原定义逐字一致。
// STORAGE_KEY 变更会丢老用户的本地数据，动前必三思。

export const STORAGE_KEY = 'knowledgeops-agent-react-console-v2';
export const LEGACY_STORAGE_KEY = 'knowledgeops-agent-react-console';
export const DEFAULT_SYSTEM_MESSAGE = '你好，我是你的知识库助手。上传文档后直接提问，回答会标注内容出处。';
export const DEFAULT_WORKSPACE = 'default';
export const ESTIMATED_ROW_HEIGHT = 156;
export const OVERSCAN_COUNT = 8;

export const DEFAULT_EVAL_DATASET = [
  {
    caseId: 'rag_001',
    category: 'rag_recall',
    chatId: 'eval-rag-a',
    question: '根据知识库，课程预约需要哪些字段？',
    expectedKeywords: ['课程', '姓名', '联系方式', '校区'],
    forbiddenKeywords: ['我不知道', '无法回答'],
  },
  {
    caseId: 'rag_002',
    category: 'rag_precision',
    chatId: 'eval-rag-b',
    question: '请总结这个 PDF 里和高温健康风险相关的内容。',
    expectedKeywords: ['高温', '风险'],
    forbiddenKeywords: ['与问题无关', '瞎编'],
  },
  {
    caseId: 'rag_003',
    category: 'citation_coverage',
    chatId: 'eval-rag-b',
    question: '回答时列出引用来源，并说明高温风险处置建议。',
    expectedKeywords: ['引用', '高温', '风险'],
    expectedCitations: ['heat'],
  },
];

// 首屏示例问题：点击即填入输入框并聚焦，降低新用户上手成本
export const welcomeSuggestions = [
  '知识库里有哪些文档？',
  '帮我总结一下知识库的内容',
  '怎样上传自己的文档？',
];

// 趋势图为手写 SVG 双轴折线图（无图表库），固定 viewBox 随容器等比缩放：左轴 tokens 右轴费用 $
export const USAGE_CHART_W = 720;
export const USAGE_CHART_H = 260;
