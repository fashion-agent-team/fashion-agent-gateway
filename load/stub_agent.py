"""부하 테스트용 가짜 agent. 게이트웨이만 재려고 GPT를 부르지 않고 정해진 시간 뒤에 같은 답을 돌려줍니다.

    python3 load/stub_agent.py            # 8081에서, 답하는 데 0.8초
    STUB_DELAY=2 python3 load/stub_agent.py

게이트웨이는 AGENT_URL=http://localhost:8081 로 띄웁니다. 실제 서버(젯슨)에는 쓰지 않습니다.
"""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY = float(os.environ.get("STUB_DELAY", "0.8"))
PORT = int(os.environ.get("STUB_PORT", "8081"))
REPLY = json.dumps({
    "sessionId": "load-test",
    "speech": "오늘은 맑고 12도예요. 얇은 니트에 가벼운 겉옷을 추천해요.",
    "next": "listen",
}, ensure_ascii=False).encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        time.sleep(DELAY)
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(REPLY)))
        self.end_headers()
        self.wfile.write(REPLY)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"가짜 agent: http://localhost:{PORT} (답 {DELAY}초)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
