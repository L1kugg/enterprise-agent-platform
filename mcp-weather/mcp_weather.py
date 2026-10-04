#!/usr/bin/env python3
"""天气 MCP 翻译壳：把本项目的 JSON-RPC tools/call 桥接到 Open-Meteo 免费天气 API。

本项目 HttpMcpToolAdapter 发的请求形如：
    POST /mcp/tools/call
    {"jsonrpc":"2.0","id":"...","method":"tools/call",
     "params":{"name":"get_weather","arguments":{"city":"长春"}}}

约定（与适配器的错误语义对齐）：
- 成功：HTTP 200，返回 {"jsonrpc":"2.0","id":...,"result":{天气数据}}
- 工具级失败（查无城市/上游故障）：HTTP 200，返回 {"status":"error","message":"..."}
  —— McpToolRuntime 见 status=error 会转成 error 观测喂给模型。
- 协议垃圾（method 不对/缺参数）：同样 200 + status=error，不让适配器当成网络故障去重试。

只用 Python 标准库，镜像无需 pip 安装（服务器在国内，装依赖反而容易卡）。
"""
import json
import os
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "9101"))
UPSTREAM_TIMEOUT = 3.5  # 秒；geocode + forecast 各一次，总和须小于主应用侧 8s 工具超时

GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

# WMO weather code → 中文描述（Open-Meteo 返回的是数字编码）
WEATHER_CODES = {
    0: "晴", 1: "大致晴朗", 2: "多云", 3: "阴",
    45: "雾", 48: "雾凇",
    51: "小毛毛雨", 53: "毛毛雨", 55: "大毛毛雨",
    56: "冻毛毛雨（小）", 57: "冻毛毛雨（大）",
    61: "小雨", 63: "中雨", 65: "大雨",
    66: "冻雨（小）", 67: "冻雨（大）",
    71: "小雪", 73: "中雪", 75: "大雪", 77: "雪粒",
    80: "小阵雨", 81: "阵雨", 82: "强阵雨",
    85: "小阵雪", 86: "大阵雪",
    95: "雷阵雨", 96: "雷阵雨伴小冰雹", 99: "雷阵雨伴大冰雹",
}


def describe_weather_code(code):
    if code is None:
        return "未知"
    return WEATHER_CODES.get(int(code), f"编码 {code}")


def fetch_json(url, params):
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(url + "?" + query,
                                 headers={"User-Agent": "knowledgeops-mcp-weather/1.0"})
    with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def geocode(city):
    """城市名 → 经纬度；查无结果返回 None。"""
    data = fetch_json(GEOCODING_URL, {"name": city, "count": 1, "language": "zh", "format": "json"})
    results = data.get("results") or []
    return results[0] if results else None


def get_weather(city):
    """查天气主流程：geocode → forecast，拼一份模型好读的中文摘要。"""
    if not city or not str(city).strip():
        return {"status": "error", "message": "缺少参数 city（要查询的城市名）"}
    city = str(city).strip()

    try:
        loc = geocode(city)
    except Exception as ex:
        return {"status": "error", "message": f"城市定位服务暂不可用: {ex}"}
    if loc is None:
        return {"status": "error", "message": f"找不到城市：{city}"}

    try:
        data = fetch_json(FORECAST_URL, {
            "latitude": loc["latitude"],
            "longitude": loc["longitude"],
            "current": "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
            "daily": "temperature_2m_max,temperature_2m_min,weather_code",
            "forecast_days": 1,
            "timezone": "auto",
        })
    except Exception as ex:
        return {"status": "error", "message": f"天气数据服务暂不可用: {ex}"}

    cur = data.get("current") or {}
    daily = data.get("daily") or {}
    today_max = (daily.get("temperature_2m_max") or [None])[0]
    today_min = (daily.get("temperature_2m_min") or [None])[0]

    return {
        "city": loc.get("name", city),
        "country": loc.get("country", ""),
        "admin1": loc.get("admin1", ""),
        "current": {
            "temperature_c": cur.get("temperature_2m"),
            "weather": describe_weather_code(cur.get("weather_code")),
            "humidity_percent": cur.get("relative_humidity_2m"),
            "wind_kmh": cur.get("wind_speed_10m"),
        },
        "today": {
            "max_c": today_max,
            "min_c": today_min,
            "weather": describe_weather_code((daily.get("weather_code") or [None])[0]),
        },
        "summary": (
            f"{loc.get('name', city)}当前{describe_weather_code(cur.get('weather_code'))}，"
            f"气温 {cur.get('temperature_2m')}°C（今日 {today_min}~{today_max}°C），"
            f"湿度 {cur.get('relative_humidity_2m')}%，风速 {cur.get('wind_speed_10m')} km/h。"
        ),
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # 跟随 docker logs 的简洁风格
        print("mcp-weather:", fmt % args, flush=True)

    def _send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/health", "/healthz"):
            self._send_json({"status": "ok"})
        else:
            self._send_json({"status": "error", "message": "not found"}, status=404)

    def do_POST(self):
        if self.path != "/mcp/tools/call":
            self._send_json({"status": "error", "message": "not found"}, status=404)
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > 64 * 1024:
                self._send_json({"status": "error", "message": "request body missing or too large"})
                return
            req = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            self._send_json({"status": "error", "message": "request body is not valid JSON"})
            return

        rpc_id = req.get("id")
        if req.get("method") != "tools/call":
            self._send_json({"status": "error", "message": "only method tools/call is supported"})
            return
        params = req.get("params") or {}
        tool = params.get("name")
        arguments = params.get("arguments") or {}
        if tool != "get_weather":
            self._send_json({"status": "error",
                             "message": f"unknown tool: {tool}（本壳只提供 get_weather）"})
            return

        result = get_weather(arguments.get("city"))
        if "status" in result and result.get("status") == "error":
            self._send_json(result)
        else:
            self._send_json({"jsonrpc": "2.0", "id": rpc_id, "result": result})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"mcp-weather shell listening on :{PORT}", flush=True)
    server.serve_forever()
