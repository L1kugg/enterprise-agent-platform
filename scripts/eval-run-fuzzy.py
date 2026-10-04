# -*- coding: utf-8 -*-
"""刁钻组评测：同一批知识点，模糊问法（不含书里原词），考验检索容错。
用法：EVAL_PASS=密码 EVAL_USER=selftest02 python scripts/eval-run-fuzzy.py
"""
import json
import os
import sys
import urllib.request

BASE = 'http://82.157.60.115:8088/api'
USER = os.environ.get('EVAL_USER', 'selftest02')
PASSWORD = os.environ.get('EVAL_PASS', '')

DOC_TAG = 'mianshiya'

# 问法刻意绕开原文表述：不提"隔离级别/最左前缀/回表"等术语
FUZZY_CASES = [
    {"category": "fuzzy", "question": "俩人同时改同一条数据，怎么保证不打架？",
     "expectedKeywords": ["可重复读", "mvcc"]},
    {"category": "fuzzy", "question": "我给三个字段一起建了个索引，为啥只拿中间那个字段查就快不起来？",
     "expectedKeywords": ["最左前缀"]},
    {"category": "fuzzy", "question": "为什么老鸟都劝我查表别用星号？这跟索引有啥关系？",
     "expectedKeywords": ["回表", "覆盖索引"]},
    {"category": "fuzzy", "question": "想知道表里一共有多少条记录，怎么写最稳妥不出错？",
     "expectedKeywords": ["count(*)", "null"]},
    {"category": "fuzzy", "question": "数据多到一张表装不下了，有什么拆开的办法？",
     "expectedKeywords": ["分库分表", "垂直分库"]},
    {"category": "fuzzy", "question": "商品页打开太慢，想在数据库前面垫一层快的地方，用什么？",
     "expectedKeywords": ["redis", "缓存"]},
    {"category": "fuzzy", "question": "好几个任务抢着往同一个 Map 里写东西，会不会出错？该换哪个？",
     "expectedKeywords": ["concurrenthashmap", "cas"]},
    {"category": "fuzzy", "question": "想存一堆不能重复的标签，选什么容器比较合适？",
     "expectedKeywords": ["hashset"]},
]

for i, c in enumerate(FUZZY_CASES):
    c["caseId"] = "fuzzy-%03d" % (i + 1)
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

    ds = call('/ai/evaluation/datasets', 'POST', {
        'name': 'Java面试题 刁钻组（模糊问法）',
        'description': '同样 8 个知识点，问法换成口语化描述且不含原文术语，考验检索容错与语义理解',
        'cases': FUZZY_CASES,
    }, headers=h)
    if ds.get('ok') is not None and ds.get('ok') != 1:
        print('CREATE DATASET FAILED:', ds.get('msg'))
        sys.exit(1)
    dataset_id = ds['datasetId']
    print('dataset:', dataset_id, 'cases:', ds.get('caseCount'))

    run = call('/ai/evaluation/datasets/%s/runs' % dataset_id, 'POST',
               {'modelProfile': 'balanced', 'chatIdPrefix': 'eval-java-fuzzy'}, headers=h, timeout=1800)
    if run.get('ok') is not None and run.get('ok') != 1:
        print('RUN FAILED:', run.get('msg'))
        sys.exit(1)

    with open('evaluation/results/eval-result-fuzzy.json', 'w', encoding='utf-8') as f:
        json.dump({'datasetId': dataset_id, 'run': run}, f, ensure_ascii=False, indent=2)
    print('run:', run.get('runId'), 'status:', run.get('status'))
    print('saved to evaluation/results/eval-result-fuzzy.json')


if __name__ == '__main__':
    main()
