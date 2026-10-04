# -*- coding: utf-8 -*-
"""多文档抗干扰评测：目标文档 + 受控干扰文档 + 天然干扰文档同租户共存的条件下
测检索的文档区分能力。

文档布局（同租户 selftest02，共 3 份）：
  A. deepresearch-test-knowledge.md   目标文档：企业内部 RAG 选型评估（Keystone/Compass/Atlas/Forge）
  B. interference-consumer-rag.md     受控干扰：消费级 RAG 市场调研（智问Pro/FileChat/灵犀/问问盒子/青藤）
                                       —— 与 A 主题词高度重叠（幻觉率/延迟/成本/"胡说八道"），
                                          实体与数字完全不同
  C. Java 面试题 PDF                   天然干扰：主题无关，考基础噪声下检索是否稳定

用法：
  EVAL_BASE=http://<host>/api EVAL_USER=selftest02 EVAL_PASS=xxx \
  python scripts/eval_multidoc_interference.py

判定矩阵：
  - scoped_* 题：问题显式限定文档 A 语境，答案必须取 A，命中 B 的数字 = 串文档
  - bothdocs_* 题：问题跨两份文档，答案应同时引用 A 与 B 的实体
  - trap_crossdoc：干扰文档 B 有"一本正经地胡说八道"原话但数字属于消费产品，
    问企业评估语境时必须答 Forge 而非问问盒子——最强的串扰测试
"""
import json
import os
import sys
import urllib.request

BASE = os.environ.get('EVAL_BASE', 'http://localhost:8080')
USER = os.environ.get('EVAL_USER', 'selftest02')
PASSWORD = os.environ.get('EVAL_PASS', '')

DOC_A = 'deepresearch-test-knowledge'   # 目标：企业选型报告
DOC_B = 'interference-consumer-rag'     # 干扰：消费市场调研

CASES = [
    # ---- 组1：限定文档 A 语境，抗 B 的词汇重叠干扰 ----
    # forbidden 设计教训（首测踩坑）：正确答案在"点名并排除"干扰文档时会合法提到
    # B 的产品名与数字（如"问问盒子22.1%属消费级，与本评估无关"），子串匹配无法
    # 区分"引用B作答"与"排除B作答"。因此 forbidden 只放完整错误结论句式，
    # 不放 B 的裸实体名/裸数字。
    {"category": "scoped_a", "question": "在公司内部的企业级RAG选型评估中，哪个方案幻觉率最高？",
     "expectedKeywords": ["forge", "24.0"],   # 注意：答案常写"24.0%"，写"24%"匹配不上（子串）
     "forbiddenKeywords": ["幻觉率最高的是问问盒子", "幻觉率最高的是智问", "幻觉率最高的是filechat", "幻觉率最高的是灵犀"]},

    {"category": "scoped_a", "question": "公司内部评估推荐的Keystone方案三年TCO是多少？由哪些技术组件构成？",
     "expectedKeywords": ["102", "spring", "pgvector"],
     # forbidden 教训：评测器把 forbidden 匹配到"答案+检索证据"池——检索带回 B 的
     # 订阅价格片段（含"每月/288/370"）就误杀满分答案。故不放任何 B 文档词汇
     "forbiddenKeywords": ["keystone是订阅制", "keystone按月收费"]},

    # ---- 组2：限定文档 B 语境，反向抗 A 干扰 ----
    {"category": "scoped_b", "question": "消费级RAG助手的实测调研中，哪款产品幻觉率最低？是多少？",
     "expectedKeywords": ["青藤", "7.3"],
     "forbiddenKeywords": ["幻觉率最低的是keystone", "幻觉率最低的是forge", "幻觉率最低的是atlas"]},

    {"category": "scoped_b", "question": "市场调研报告里用户抱怨哪款产品最爱一本正经地胡说八道？编造了什么内容？",
     "expectedKeywords": ["问问盒子", "保修"],
     "forbiddenKeywords": ["最爱胡说八道的是forge", "最爱胡说八道的是keystone"]},

    # ---- 组3：跨文档综合，两份都要引用 ----
    {"category": "bothdocs", "question": "企业级评估和消费级调研里各自测得的最低幻觉率分别是多少？分别属于哪个方案或产品？",
     "expectedKeywords": ["6.5", "keystone", "7.3", "青藤"],
     "forbiddenKeywords": []},

    # ---- 组4：最强串扰陷阱 ----
    {"category": "trap_crossdoc", "question": "在公司企业级RAG选型评估的语境下，哪个平台最容易一本正经地胡说八道？",
     "expectedKeywords": ["forge", "24.0"],
     "forbiddenKeywords": ["最容易胡说八道的是问问盒子", "最容易胡说八道的是智问", "最容易胡说八道的是filechat", "最容易胡说八道的是灵犀", "最容易胡说八道的是青藤"]},

    # ---- 组5：噪声稳定性（C 文档存在时基础检索不受影响） ----
    {"category": "noise_stability", "question": "企业内部评估中Atlas方案为什么被否决？",
     "expectedKeywords": ["atlas", "合规", "一票否决"],
     "forbiddenKeywords": ["atlas被否决是因为订阅费", "问问盒子导致atlas被否决"]},
]

for i, c in enumerate(CASES):
    c["caseId"] = "md-%03d" % (i + 1)


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
        'name': '多文档抗干扰评测（3文档共存）',
        'description': '目标文档A（企业选型）+受控干扰B（消费调研，词汇重叠数字不同）+天然干扰C（Java面试题）。'
                       '测检索的文档区分能力：scoped_a/scoped_b 抗串扰、bothdocs 跨文档综合、trap_crossdoc 最强陷阱',
        'cases': CASES,
    }, headers=h)
    if ds.get('ok') is not None and ds.get('ok') != 1:
        print('CREATE DATASET FAILED:', ds.get('msg'))
        sys.exit(1)
    dataset_id = ds['datasetId']
    print('dataset:', dataset_id, 'cases:', ds.get('caseCount'))

    run = call('/ai/evaluation/datasets/%s/runs' % dataset_id, 'POST',
               {'modelProfile': 'balanced', 'chatIdPrefix': 'eval-multidoc'}, headers=h, timeout=1800)
    if run.get('ok') is not None and run.get('ok') != 1:
        print('RUN FAILED:', run.get('msg'))
        sys.exit(1)

    with open('evaluation/results/eval-result-multidoc.json', 'w', encoding='utf-8') as f:
        json.dump({'datasetId': dataset_id, 'run': run}, f, ensure_ascii=False, indent=2)
    print('run:', run.get('runId'), 'status:', run.get('status'))

    metrics = run.get('metrics') or {}
    print('runScore:', metrics.get('runScore'),
          'retrievalHitRate:', metrics.get('retrievalHitRate'),
          'faithfulness:', metrics.get('answerFaithfulnessScore'))

    print()
    print('== per-case ==')
    for r in run.get('results', []):
        print('%s | %s | kw=%s cite=%s | %sms' % (
            r['caseId'], r['status'],
            r.get('keywordScore'), r.get('citationCoverage'), r.get('latencyMs')))


if __name__ == '__main__':
    main()
