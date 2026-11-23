"""edge-tts 사이드카 테스트. 마이크로소프트 서버에는 붙지 않고 synthesize를 가짜로 바꿔 HTTP 계층만 봅니다.

실행: cd edge-tts && pip install -r requirements.txt -r tests/requirements.txt && python -m pytest tests -q
"""
import asyncio

import pytest

import server


@pytest.fixture
def calls(monkeypatch):
    seen = []

    async def fake(text, voice, rate):
        seen.append((text, voice, rate))
        return b"ID3fake-mp3"

    monkeypatch.setattr(server, "synthesize", fake)
    return seen


@pytest.fixture
async def client(aiohttp_client):
    return await aiohttp_client(server.app())


async def test_health는_ok(client):
    res = await client.get("/health")
    assert res.status == 200
    assert await res.text() == "ok"


async def test_정상_요청은_mp3와_기본_목소리_속도(client, calls):
    res = await client.post("/tts", json={"text": "  안녕하세요, 심청이예요.  "})
    assert res.status == 200
    assert res.content_type == "audio/mpeg"
    assert await res.read() == b"ID3fake-mp3"
    assert calls == [("안녕하세요, 심청이예요.", "ko-KR-SunHiNeural", "+0%")]


async def test_목소리와_속도를_그대로_넘긴다(client, calls):
    res = await client.post("/tts", json={"text": "네", "voice": "ko-KR-InJoonNeural", "rate": "-20%"})
    assert res.status == 200
    assert calls == [("네", "ko-KR-InJoonNeural", "-20%")]


async def test_JSON이_아니면_400_bad_json(client, calls):
    res = await client.post("/tts", data="text=hi", headers={"Content-Type": "application/json"})
    assert res.status == 400
    assert (await res.json())["error"] == "bad_json"
    assert calls == []


@pytest.mark.parametrize("text", ["", "   ", None, "가" * (server.MAX_CHARS + 1)])
async def test_글이_비었거나_1000자_넘으면_400_bad_text(client, calls, text):
    res = await client.post("/tts", json={"text": text})
    assert res.status == 400
    assert (await res.json())["error"] == "bad_text"
    assert calls == []


async def test_1000자_딱이면_읽는다(client, calls):
    res = await client.post("/tts", json={"text": "가" * server.MAX_CHARS})
    assert res.status == 200


@pytest.mark.parametrize("body", [
    {"voice": "ko-KR-SunHi"},            # Neural로 안 끝남
    {"voice": "../../etc/passwd"},
    {"voice": "ko-KR-SunHiNeural; rm"},
    {"rate": "20%"},                    # 부호 없음
    {"rate": "+1000%"},
    {"rate": "fast"},
])
async def test_이상한_목소리나_속도는_400(client, calls, body):
    res = await client.post("/tts", json={"text": "안녕", **body})
    assert res.status == 400
    assert (await res.json())["error"] == "bad_voice_or_rate"
    assert calls == []


async def test_엣지_연결_실패는_502_edge_failed(client, monkeypatch):
    async def boom(text, voice, rate):
        raise ConnectionError("speech.platform.bing.com")

    monkeypatch.setattr(server, "synthesize", boom)
    res = await client.post("/tts", json={"text": "안녕"})
    assert res.status == 502
    assert await res.json() == {"error": "edge_failed", "detail": "ConnectionError"}


async def test_시간_초과는_502_edge_failed(client, monkeypatch):
    async def slow(text, voice, rate):
        await asyncio.sleep(5)
        return b"late"

    monkeypatch.setattr(server, "synthesize", slow)
    monkeypatch.setattr(server, "TIMEOUT", 0.05)
    res = await client.post("/tts", json={"text": "안녕"})
    assert res.status == 502
    assert (await res.json())["detail"] == "TimeoutError"


async def test_소리가_비면_502_empty_audio(client, monkeypatch):
    async def empty(text, voice, rate):
        return b""

    monkeypatch.setattr(server, "synthesize", empty)
    res = await client.post("/tts", json={"text": "안녕"})
    assert res.status == 502
    assert (await res.json())["error"] == "empty_audio"


async def test_64KB_넘는_요청은_읽지_않고_400(client, calls):
    # client_max_size(64KB)를 넘으면 aiohttp가 413을 던지는데, tts()가 body 읽기 예외를 모두 bad_json으로 받는다.
    res = await client.post("/tts", data=b"{" + b" " * (65 * 1024) + b"}", headers={"Content-Type": "application/json"})
    assert res.status == 400
    assert (await res.json())["error"] == "bad_json"
    assert calls == []


async def test_synthesize는_audio_조각만_이어_붙인다(monkeypatch):
    class FakeCommunicate:
        def __init__(self, text, voice, rate, connect_timeout, receive_timeout):
            self.args = (text, voice, rate)

        async def stream(self):
            yield {"type": "audio", "data": b"ab"}
            yield {"type": "WordBoundary", "offset": 1}
            yield {"type": "audio", "data": b"cd"}

    monkeypatch.setattr(server.edge_tts, "Communicate", FakeCommunicate)
    assert await server.synthesize("안녕", "ko-KR-SunHiNeural", "+0%") == b"abcd"
