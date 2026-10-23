#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""电商售后客服演示场景一键安装/清除。

在目标环境（本地 demo 或生产）搭建一个完整的"电商售后智能客服"业务场景：
  1. 业务库建表 + 种子数据（biz_orders / biz_products / biz_refunds，含租户隔离对照数据）；
  2. 创建知识库资产并上传 5 篇售后文档（退换货政策/物流说明/会员规则/保修FAQ/客服流程）；
  3. 解析系统共享 LLM 模型服务（优先 qwen 系列），创建智能体并绑定模型 + 知识库，发布上架。

用法（项目根目录，Git Bash / PowerShell 均可）：
  python scripts/setup_ecommerce_demo.py --api-base http://82.157.60.115:8080 \
      --api-key sk-admin-xxx --server ubuntu@82.157.60.115 \
      --mysql-container enterprise-agent-platform-mysql --db enterprise_agent_platform

  python scripts/setup_ecommerce_demo.py ... --teardown   # 清除全部演示数据（幂等）

仅用标准库 + 本机 curl/ssh/scp（密钥免密），不依赖第三方包。
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = ROOT / "demo-data" / "ecommerce" / "docs"
SQL_FILE = ROOT / "demo-data" / "ecommerce" / "schema-seed.sql"
KB_CHAT_ID = "kb-ecommerce-demo"
DOCS = ["退换货政策.md", "物流与发货说明.md", "会员与优惠券规则.md", "产品保修与使用FAQ.md", "售后处理流程.md"]

AGENT_NAME = "小惠售后助手"
KB_NAME = "售后服务中心"

AGENT_PROMPT = (
    "你是星辉商城的售后客服助手「小惠」。处理用户售后请求时："
    "1) 凡是订单号（SO 开头 12 位）、退款、物流的问题，必须先用 query_database 工具查业务库再回答，"
    "禁止让用户去 App 查看，禁止编造订单状态。业务库有三张表（都带 tenant_id 列，"
    "SQL 里必须写 tenant_id = '__TENANT__' 这个占位符）："
    "biz_orders（订单：order_no 订单号, customer_name 客户, product_name 商品, quantity 数量, amount 金额, "
    "status 状态:待支付/待发货/已发货/已签收/退款中/已完成/已关闭, shipping_status 物流状态:未发货/运输中/已送达/已退回, "
    "logistics_no 物流单号, logistics_company 承运公司, created_at 下单时间）、"
    "biz_products（商品：product_name, category, price, stock 库存, warehouse 仓库, warranty_months 保修月数）、"
    "biz_refunds（退款单：refund_no, order_no, reason 原因, refund_amount 金额, "
    "status:待审核/已同意/已退款/已拒绝, applied_at 申请时间）。"
    "查询示例：SELECT order_no, status, shipping_status, logistics_no, logistics_company, amount FROM biz_orders "
    "WHERE tenant_id = '__TENANT__' AND order_no = 'SO2026100103'。"
    "2) 涉及政策、时效、规则的，依据知识库原文回答并说明依据；"
    "3) 查不到订单时请用户核对订单号（SO 开头 12 位）；"
    "4) 退款到账时效依据《退换货政策》第四节如实告知，不要承诺当天到账；"
    "5) 超出政策范围的承诺不要给，涉及 3000 元以上退款建议转人工。"
)
OPENING_MESSAGE = "您好，我是星辉商城售后助手小惠 🎧 查订单、退换货、保修政策都可以问我。"
OPENING_QUESTIONS = [
    "查一下订单 SO2026100103 现在什么状态",
    "耳机有杂音想退货，符合政策吗",
    "扫地机器人保修多久？异响怎么处理",
    "会员积分怎么算，能抵多少钱",
]


def sh(args, **kw):
    """subprocess.run 封装：参数列表直传，不经 shell，绕开引号转义坑。"""
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", **kw)


def api(method, path, api_base, api_key, payload=None, field=None):
    """调平台 API：JSON 用 --data @临时文件（UTF-8 无转义风险），multipart 走 -F。"""
    cmd = ["curl", "-s", "-m", "120", "-X", method,
           "-H", f"X-API-Key: {api_key}"]
    if payload is not None:
        tmp = Path("/tmp/ecom_demo_payload.json")
        tmp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        cmd += ["-H", "Content-Type: application/json", "--data", f"@{tmp.as_posix()}"]
    if field is not None:
        cmd += ["-F", field]
    cmd.append(f"{api_base}{path}")
    result = sh(cmd)
    if result.returncode != 0:
        raise RuntimeError(f"curl 失败: {result.stderr}")
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        raise RuntimeError(f"API 返回非 JSON: {result.stdout[:300]}")


def apply_sql(args):
    print("==> [1/5] 业务库建表 + 种子数据")
    tmp_remote = "/tmp/ecom_demo_schema.sql"
    r = sh(["scp", str(SQL_FILE), f"{args.server}:{tmp_remote}"])
    if r.returncode != 0:
        raise RuntimeError(f"scp SQL 失败: {r.stderr}")
    # 复杂远端命令写临时脚本经 ssh "bash -s" 管道执行（引号转义必炸的可靠规避）
    remote_script = f"""set -e
PW=$(sudo docker exec {args.mysql_container} printenv MYSQL_ROOT_PASSWORD)
sudo docker exec -i -e MYSQL_PWD="$PW" {args.mysql_container} \\
  mysql -uroot --default-character-set=utf8mb4 {args.db} < {tmp_remote}
sudo docker exec {args.mysql_container} sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \\
  --default-character-set=utf8mb4 {args.db} -N -e "SELECT COUNT(*) FROM biz_orders; SELECT COUNT(*) FROM biz_products;"'
rm -f {tmp_remote}
"""
    script_path = Path("/tmp/ecom_demo_apply.sh")
    # Windows 上必须显式 LF：text 模式默认写 CRLF，远端 bash 会把 \r 当命令的一部分
    script_path.write_text(remote_script, encoding="utf-8", newline="\n")
    r = subprocess.run(["ssh", args.server, "bash -s"],
                       stdin=open(script_path, "r", encoding="utf-8"),
                       capture_output=True, text=True)
    script_path.unlink(missing_ok=True)
    if r.returncode != 0:
        raise RuntimeError(f"远端建表失败: {r.stdout}\n{r.stderr}")
    counts = [line for line in r.stdout.strip().splitlines() if line.strip().isdigit()]
    print(f"    建表完成：biz_orders={counts[0] if counts else '?'} 条，biz_products={counts[1] if len(counts) > 1 else '?'} 条")


def pick_model(args):
    print("==> [2/5] 解析系统共享 LLM 模型服务")
    payload = api("GET", "/platform/model-services", args.api_base, args.api_key)
    items = payload.get("items") if isinstance(payload, dict) else payload
    llm = []
    for item in items or []:
        try:
            config = json.loads(item.get("configJson") or "{}")
        except json.JSONDecodeError:
            continue
        if config.get("category", "").upper() == "LLM":
            llm.append((item["id"], item.get("name", ""), config.get("modelName", ""),
                        item.get("scope", "")))
    if not llm:
        raise RuntimeError("没有可用的 LLM 模型服务，请先在模型服务页创建")
    system = [m for m in llm if m[3] == "SYSTEM"]
    pool = system or llm
    preferred = [m for m in pool if "qwen" in (m[2] or "").lower()] or pool
    model_id, name, model_name, scope = preferred[0]
    print(f"    使用模型服务: {name}（{model_name}，scope={scope}，id={model_id}）")
    return model_id


def create_kb_and_docs(args):
    print("==> [3/5] 创建知识库并上传售后文档")
    kb = api("POST", "/platform/knowledge-bases", args.api_base, args.api_key,
             payload={"name": KB_NAME, "description": "电商售后演示知识库：退换货/物流/会员/保修政策",
                      "configJson": "{}"})
    kb_id = kb["id"]
    print(f"    知识库 id={kb_id}")
    for doc in DOCS:
        path = DOCS_DIR / doc
        upload = api("POST", f"/ingestion/upload/{KB_CHAT_ID}?knowledgeBaseId={kb_id}",
                     args.api_base, args.api_key,
                     field=f"file=@{path.as_posix()};type=text/markdown")
        print(f"    已上传: {doc}（job={upload.get('jobId', '?')}）")

    deadline = time.time() + 180
    while time.time() < deadline:
        jobs = api("GET", f"/ingestion/jobs?chatId={KB_CHAT_ID}&limit=20",
                   args.api_base, args.api_key)
        statuses = [j.get("status") for j in (jobs if isinstance(jobs, list) else jobs.get("items", []))]
        if statuses and all(s == "SUCCEEDED" for s in statuses):
            print(f"    全部 {len(statuses)} 篇文档解析入库完成")
            return kb_id
        if "FAILED" in statuses:
            raise RuntimeError(f"文档解析失败: {statuses}")
        time.sleep(5)
    raise RuntimeError(f"文档解析超时，当前状态: {statuses}")


def create_agent(args, model_id, kb_id):
    print("==> [4/5] 创建并发布智能体")
    config = {
        "prompt": AGENT_PROMPT,
        "memoryTurns": 10,
        "openingMessage": OPENING_MESSAGE,
        "openingQuestions": OPENING_QUESTIONS,
        "modelId": model_id,
        "knowledgeBaseIds": [kb_id],
        "publishChannels": ["web"],
        "executionConfirm": False,
    }
    agent = api("POST", "/platform/agents", args.api_base, args.api_key,
                payload={"name": AGENT_NAME, "description": "电商售后客服演示：订单查询(text2sql) + 售后政策问答(RAG)",
                         "configJson": json.dumps(config, ensure_ascii=False)})
    agent_id = agent["id"]
    api("POST", f"/platform/agents/{agent_id}/publish", args.api_base, args.api_key,
        payload={"channels": ["web"]})
    print(f"    智能体「{AGENT_NAME}」已发布上架 id={agent_id}")
    return agent_id


def find_asset_id(args, path, name):
    payload = api("GET", path, args.api_base, args.api_key)
    items = payload.get("items") if isinstance(payload, dict) else payload
    for item in items or []:
        if item.get("name") == name:
            return item["id"]
    return None


def teardown(args):
    print("==> 清除演示数据（幂等）")
    for path, name in [("/platform/agents", AGENT_NAME), ("/platform/knowledge-bases", KB_NAME)]:
        asset_id = find_asset_id(args, path, name)
        if asset_id:
            r = api("DELETE", f"{path}/{asset_id}", args.api_base, args.api_key)
            print(f"    已删除资产 {name} id={asset_id}: {r.get('ok', 'ok')}")
        else:
            print(f"    资产不存在（跳过）: {name}")
    docs = api("GET", f"/ingestion/documents?chatId={KB_CHAT_ID}", args.api_base, args.api_key)
    had_docs = bool(docs if isinstance(docs, list) else docs.get("items"))
    if had_docs:
        r = api("DELETE", f"/ingestion/documents/{KB_CHAT_ID}", args.api_base, args.api_key)
        print(f"    已删除演示文档: {r if isinstance(r, list) else 'ok'}")
    remote_cmd = (
        f"sudo docker exec {args.mysql_container} sh -c "
        f"'mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" {args.db} "
        f"-e \"DROP TABLE IF EXISTS biz_orders; DROP TABLE IF EXISTS biz_products; "
        f"DROP TABLE IF EXISTS biz_refunds;\"'"
    )
    r = sh(["ssh", args.server, remote_cmd])
    if r.returncode != 0:
        raise RuntimeError(f"删表失败: {r.stderr}")
    print("    业务表已删除（biz_orders / biz_products / biz_refunds）")
    print("✅ 演示场景已清除")


def main():
    parser = argparse.ArgumentParser(description="电商售后客服演示场景一键安装")
    parser.add_argument("--api-base", default="http://82.157.60.115:8080")
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--server", default="ubuntu@82.157.60.115", help="SSH 目标（执行建表 SQL）；本地库可传空串跳过")
    parser.add_argument("--mysql-container", default="enterprise-agent-platform-mysql")
    parser.add_argument("--db", default="enterprise_agent_platform")
    parser.add_argument("--teardown", action="store_true", help="清除演示数据")
    args = parser.parse_args()

    if args.teardown:
        teardown(args)
        return
    apply_sql(args)
    model_id = pick_model(args)
    kb_id = create_kb_and_docs(args)
    agent_id = create_agent(args, model_id, kb_id)
    print()
    print("✅ 电商售后客服场景搭建完成")
    print(f"   智能体: {AGENT_NAME} (id={agent_id}) | 知识库: {KB_NAME} (id={kb_id})")
    print("   打开前端 → 对话工作台 → 智能体选择器选「小惠售后助手」，试试开场问题：")
    for q in OPENING_QUESTIONS:
        print(f"   - {q}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
