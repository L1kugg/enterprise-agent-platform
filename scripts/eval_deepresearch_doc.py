# -*- coding: utf-8 -*-
"""DeepResearch 测试文档评测集驱动脚本：登录 → 建评测集 → 跑评测 → 结果落盘。

知识库：demo-data/deepresearch-test-knowledge.md（RAG 平台技术选型评估报告）
需先完成入库：POST /ingestion/upload/deepresearch-test-01

用法：
  EVAL_PASS=密码 EVAL_USER=selftest02 python scripts/eval_deepresearch_doc.py

评测设计（四层难度）：
  L1 direct_recall    —— 直接检索，子问题关键词与文档原词重合（链路基线）
  L2 paraphrase       —— 同义改写，问题不含文档原词，考向量语义检索
  L3 constrained_infer —— 约束推理，答案须综合比对多处数字，文中无现成答案
  L4 trap             —— 陷阱题，话题沾边但文档明确说无数据，考幻觉守卫

注意：Evaluation Studio 执行走 HybridRagAnswerService（同步 RAG 问答），
不含 DeepResearch 的拆题环节；L3 的"口径对齐"表现需另经
POST /ai/research/tasks 异步链路人工核对（配套评分表见
docs/demo-deepresearch-test.md）。
"""
import json
import os
import sys
import urllib.request

BASE = os.environ.get('EVAL_BASE', 'http://localhost:8080')
USER = os.environ.get('EVAL_USER', 'selftest02')
PASSWORD = os.environ.get('EVAL_PASS', '')

DOC_TAG = 'deepresearch-test-knowledge'  # 引用串里的文件名特征

# 评分关键词设计原则：
#   expected —— 标准答案必含的事实词/结论词（对大小写不敏感，评测器 lower 后匹配）
#   forbidden —— 幻觉特征词：出现即整题零分
CASES = [
    # ---- L1 直接检索（基线） ----
    {"category": "direct_recall", "question": "对比评估中各RAG候选方案的检索命中率、幻觉率和三年TCO，哪个方案综合表现最好？",
     "expectedKeywords": ["keystone", "96.0%", "6.5%", "102"],   # 96.0 命中率 / 6.5 幻觉率 / 102 万 TCO
     "forbiddenKeywords": ["藏语", "准确率91", "编造"]},

    {"category": "direct_recall", "question": "Keystone 方案采用了哪些技术组件构建？性能表现如何？",
     "expectedKeywords": ["spring", "pgvector", "rabbitmq", "240"],  # P95 240ms
     "forbiddenKeywords": ["milvus 集群", "一票否决"]},

    # ---- L2 同义改写（考向量语义） ----
    {"category": "paraphrase", "question": "哪个平台最容易一本正经地胡说八道？",
     "expectedKeywords": ["forge", "24%"],   # 幻觉率 24% 为四方案最高
     "forbiddenKeywords": ["keystone 最容易", " hallucination rate 6.5% 最高"]},

    {"category": "paraphrase", "question": "有没有方案因为数据不能放在自己手里而被毙掉？",
     "expectedKeywords": ["atlas", "合规", "一票否决"],   # 云托管 + 本地化留存不满足
     "forbiddenKeywords": ["forge 合规", "keystone 被毙"]},

    {"category": "paraphrase", "question": "公司最后选中的方案，养它三年总共要花多少钱？",
     "expectedKeywords": ["keystone", "102"],   # 三年 TCO 约 102 万
     "forbiddenKeywords": ["232", "76.6万三年"]},

    # ---- L3 约束推理（文中无现成答案） ----
    {"category": "constrained_infer", "question": "如果公司规定每年基础设施预算不得超过25万元，哪些方案直接出局，为什么？",
     "expectedKeywords": ["atlas", "76.6", "keystone", "19"],  # 正确口径：Atlas 出局；Keystone 21万/Compass 19万 存活
     "forbiddenKeywords": ["34万", "40.3万", "三个方案直接出局", "全部出局"]},  # 34=102÷3、40.3=121÷3：把三年TCO摊年是不声明口径的错推导

    {"category": "constrained_infer", "question": "按端到端P95延迟衡量，响应速度最快的方案为什么最后没有被选上？",
     # 注：必须限定"端到端"——文档另列有"向量检索 P95"（Compass 180ms 最低），
     # 不限定时模型选 Compass 读法同样成立（实测踩过），题就废了
     "expectedKeywords": ["atlas", "420", "合规", "一票否决"],  # 端到端 420ms 最快 + 合规出局（两跳连接）
     "forbiddenKeywords": ["延迟最高的是atlas", "响应速度最慢的是compass", "keystone响应最快"]},

    # ---- L4 陷阱题（幻觉守卫） ----
    {"category": "trap", "question": "文档评估了哪些少数民族语言的检索效果，结果如何？",
     "expectedKeywords": ["未测试", "二期"],   # 文档原文：方言与少数民族语言未测试，二期补充
     "forbiddenKeywords": ["准确率", "藏语检索", "覆盖率", "%的准确"]},  # 编造具体数字 = 幻觉实锤

    {"category": "trap", "question": "评估报告里四套方案在评估期间分别暴露过什么问题？哪个性质最严重？",
     "expectedKeywords": ["脑裂", "跨租户", "噪声"],   # Compass 泄露+脑裂 / Keystone 图谱噪声 / Forge 幻觉
     "forbiddenKeywords": ["零缺陷", "无任何问题"]},
]

for i, c in enumerate(CASES):
    c["caseId"] = "case-%03d" % (i + 1)
    c["expectedCitations"] = [DOC_TAG]


def call(path, method='GET', payload=None, headers=None, timeout=900):
    data = json.dumps(payload).encode('utf-8') if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers=dict({'Content-Type': 'application/json'}, **(headers or {})))
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8'))


def main():
    if not PASSWORD:
        print('缺少 EVAL_PASS 环境变量')
        sys.exit(1)
    login = call('/auth/login', 'POST', {'username': USER, 'password': PASSWORD})
    if login.get('ok') != 1:
        print('LOGIN FAILED:', login.get('msg'))
        sys.exit(1)
    h = {'Authorization': 'Bearer ' + login['token']}

    ds = call('/ai/evaluation/datasets', 'POST', {
        'name': 'DeepResearch 测试文档四层难度评测',
        'description': '基于 RAG 平台技术选型评估报告（deepresearch-test-knowledge.md）：L1 直接检索 / L2 同义改写 / L3 约束推理 / L4 陷阱守卫，共 9 题',
        'cases': CASES,
    }, headers=h)
    if ds.get('ok') is not None and ds.get('ok') != 1:
        print('CREATE DATASET FAILED:', ds.get('msg'))
        sys.exit(1)
    dataset_id = ds['datasetId']
    print('dataset:', dataset_id, 'cases:', ds.get('caseCount'))

    run = call('/ai/evaluation/datasets/%s/runs' % dataset_id, 'POST',
               {'modelProfile': 'balanced', 'chatIdPrefix': 'eval-deepresearch-doc'}, headers=h, timeout=1800)
    if run.get('ok') is not None and run.get('ok') != 1:
        print('RUN FAILED:', run.get('msg'))
        sys.exit(1)

    with open('evaluation/results/eval-result-deepresearch-doc.json', 'w', encoding='utf-8') as f:
        json.dump({'datasetId': dataset_id, 'run': run}, f, ensure_ascii=False, indent=2)
    print('run:', run.get('runId'), 'status:', run.get('status'))
    print('saved to evaluation/results/eval-result-deepresearch-doc.json')

    metrics = run.get('metrics') or {}
    print('runScore:', metrics.get('runScore'),
          'retrievalHitRate:', metrics.get('retrievalHitRate'),
          'faithfulness:', metrics.get('answerFaithfulnessScore'))


if __name__ == '__main__':
    main()
