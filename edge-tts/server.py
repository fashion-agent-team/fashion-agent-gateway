"""Edge TTS 사이드카: 게이트웨이가 부르는 작은 HTTP 서버.

마이크로소프트 엣지 브라우저의 '소리 내어 읽기' 목소리(ko-KR-SunHiNeural 등)를 키 없이 무료로 씁니다.
비공식 서비스라 발표·시연용으로만 씁니다(2026-11-12 진행 님 결정, 상업용 아님).
접속 방식(Sec-MS-GEC 등)이 바뀌면 edge-tts 패키지를 올리면 됩니다(requirements.txt).

  POST /tts  {"text": "...", "voice": "ko-KR-SunHiNeural", "rate": "+0%"}  → audio/mpeg (24kHz 48kbps 모노 MP3)
  GET  /health                                                             → ok

밖에 열지 않습니다. 같은 Docker 네트워크의 게이트웨이만 부릅니다(EDGE_TTS_URL=http://edge-tts:8099).
"""

import asyncio
import logging
import os
import re
import time

import edge_tts
from aiohttp import web

log = logging.getLogger("edge-tts")

VOICE_RE = re.compile(r"^[a-z]{2,3}-[A-Z]{2}-[A-Za-z]+Neural$")
RATE_RE = re.compile(r"^[+-]\d{1,3}%$")
MAX_CHARS = 1000
TIMEOUT = float(os.environ.get("EDGE_TTS_TIMEOUT", "15"))


async def synthesize(text: str, voice: str, rate: str) -> bytes:
    audio = bytearray()
    com = edge_tts.Communicate(text, voice, rate=rate, connect_timeout=5, receive_timeout=int(TIMEOUT))
    async for chunk in com.stream():
        if chunk["type"] == "audio":
            audio.extend(chunk["data"])
    return bytes(audio)


async def tts(request: web.Request) -> web.Response:
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad_json"}, status=400)
    text = str(body.get("text") or "").strip()
    voice = str(body.get("voice") or "ko-KR-SunHiNeural")
    rate = str(body.get("rate") or "+0%")
    if not text or len(text) > MAX_CHARS:
        return web.json_response({"error": "bad_text"}, status=400)
    if not VOICE_RE.match(voice) or not RATE_RE.match(rate):
        return web.json_response({"error": "bad_voice_or_rate"}, status=400)
    started = time.monotonic()
    try:
        audio = await asyncio.wait_for(synthesize(text, voice, rate), TIMEOUT)
    except Exception as e:  # 연결 실패, 시간 초과, 목소리 없음(NoAudioReceived) 등
        log.warning("실패 %s %d자: %s: %s", voice, len(text), type(e).__name__, e)
        return web.json_response({"error": "edge_failed", "detail": type(e).__name__}, status=502)
    if not audio:
        return web.json_response({"error": "empty_audio"}, status=502)
    log.info("%s %d자 %dms", voice, len(text), (time.monotonic() - started) * 1000)
    return web.Response(body=audio, content_type="audio/mpeg")


async def health(_: web.Request) -> web.Response:
    return web.Response(text="ok")


def app() -> web.Application:
    a = web.Application(client_max_size=64 * 1024)
    a.router.add_post("/tts", tts)
    a.router.add_get("/health", health)
    return a


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    web.run_app(app(), host="0.0.0.0", port=int(os.environ.get("PORT", "8099")), access_log=None)
