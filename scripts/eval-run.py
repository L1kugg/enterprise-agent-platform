# -*- coding: utf-8 -*-
"""一次性评测驱动脚本：登录 → 确认入库 → 建评测集 → 跑评测 → 结果落盘。
用法：EVAL_PASS=密码 EVAL_USER=selftest02 python scripts/eval-run.py
密码经环境变量传入，不落日志。
"""
import json
import os
import sys
import urllib.request

BASE = 'http://82.157.60.115:8088/api'
USER = os.environ.get('EVAL_USER', 'selftest02')
PASSWORD = os.environ.get('EVAL_PASS', '')

DOC_TAG = 'mianshiya'  # 引用串里的文件名特征（ASCII，避免 PDF 内嵌字形差异踩坑）

# 10 道题全部取自 PDF 实际内容（已逐词核对过原文字形，关键词用标准写法）
CASES = [
    {"category": "mysql", "question": "MySQL 默认的事务隔离级别是什么？为什么选它？",
     "expectedKeywords": ["可重复读", "repeatable read", "mvcc"]},
    {"category": "mysql", "question": "MySQL 的索引类型有哪些？",
     "expectedKeywords": ["主键索引", "唯一索引", "联合索引", "全文索引"]},
    {"category": "mysql", "question": "联合索引的最左前缀匹配原则是什么？",
     "expectedKeywords": ["最左前缀", "范围查询"]},
    {"category": "mysql", "question": "什么是回表？怎么避免回表？",
     "expectedKeywords": ["回表", "覆盖索引"]},
    {"category": "mysql", "question": "count(*)、count(1) 和 count(字段名) 有什么区别？",
     "expectedKeywords": ["count(*)", "count(1)", "非空"]},
    {"category": "mysql", "question": "什么是分库分表？有哪些拆分策略？",
     "expectedKeywords": ["垂直分库", "sharding key", "跨库事务"]},
    {"category": "redis", "question": "Redis 通常应用在哪些场景？",
     "expectedKeywords": ["缓存", "分布式锁", "排行榜"]},
    {"category": "redis", "question": "Redis 有哪些核心数据类型？",
     "expectedKeywords": ["string", "list", "set", "sorted set"]},
    {"category": "java", "question": "Java 的集合类分为哪几大类？List 和 Set 有什么区别？",
     "expectedKeywords": ["collection", "map", "arraylist", "hashset"]},
    {"category": "java", "question": "ConcurrentHashMap 是怎么保证线程安全的？",
     "expectedKeywords": ["cas", "分段锁"]},
]

for i, c in enumerate(CASES):
    c["caseId"] = "case-%03d" % (i + 1)
    c["expectedCitations"] = [DOC_TAG]


def call(path, method='GET', payload=None, headers=None, timeout=900):
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8') if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Content-Type': 'application/json', **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def main():
    login = call('/auth/login', 'POST', {'username': USER, 'password': PASSWORD})
    if login.get('ok') != 1:
        print('LOGIN FAILED:', login.get('msg'))
        sys.exit(1)
    h = {'Authorization': 'Bearer ' + login['token']}

    jobs = call('/ingestion/jobs/recent?limit=10', headers=h)
    lines = ['== 入库任务 ==']
    for j in jobs:
        lines.append('%s | %s | chatId=%s' % (j.get('sourceName', ''), j.get('status'), j.get('chatId')))
    if not jobs:
        lines.append('(空)')
    print('\n'.join(lines))

    ds = call('/ai/evaluation/datasets', 'POST', {
        'name': 'Java面试题PDF 检索问答评测',
        'description': '基于《Java 热门面试题 200 道》PDF 知识库：MySQL/Redis/Java 集合并发 10 题',
        'cases': CASES,
    }, headers=h)
    if ds.get('ok') is not None and ds.get('ok') != 1:
        print('CREATE DATASET FAILED:', ds.get('msg'))
        sys.exit(1)
    dataset_id = ds['datasetId']
    print('dataset:', dataset_id, 'cases:', ds.get('caseCount'))

    run = call('/ai/evaluation/datasets/%s/runs' % dataset_id, 'POST',
               {'modelProfile': 'balanced', 'chatIdPrefix': 'eval-java-pdf'}, headers=h, timeout=1800)
    if run.get('ok') is not None and run.get('ok') != 1:
        print('RUN FAILED:', run.get('msg'))
        sys.exit(1)

    out = {'datasetId': dataset_id, 'run': run}
    with open('evaluation/results/eval-result.json', 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print('run:', run.get('runId'), 'status:', run.get('status'))
    print('saved to evaluation/results/eval-result.json')


if __name__ == '__main__':
    main()
