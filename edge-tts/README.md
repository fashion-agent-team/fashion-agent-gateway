# edge-tts 사이드카

게이트웨이 `POST /api/v1/tts`가 엣지 브라우저 목소리로 읽을 때 부르는 작은 서버입니다(2026-11-12).

- **발표·시연용입니다.** 마이크로소프트가 공개한 API가 아니라 엣지 '소리 내어 읽기'를 쓰는 비공식 방식이라,
  상업 출시 때는 Azure Speech(`AZURE_SPEECH_KEY`) 같은 정식 서비스로 바꿉니다.
- 키가 필요 없습니다. 인터넷만 되면 됩니다(`speech.platform.bing.com`).
- 한국어 목소리: `ko-KR-SunHiNeural`(여성, 기본), `ko-KR-InJoonNeural`(남성), `ko-KR-HyunsuMultilingualNeural`(남성).

## 띄우기

```bash
# 맥북에서 바로
cd edge-tts && pip install -r requirements.txt && python server.py      # http://localhost:8099
# 게이트웨이 .env
EDGE_TTS_URL=http://localhost:8099

# 도커(젯슨은 앱 레포 docs/infra/jetson/docker-compose.yml의 edge-tts 서비스)
docker build -t fashion-agent/edge-tts edge-tts && docker run -p 8099:8099 fashion-agent/edge-tts
```

확인: `curl -X POST localhost:8099/tts -H 'Content-Type: application/json' -d '{"text":"안녕하세요, 심청이예요."}' -o hi.mp3`

## 갑자기 안 될 때

마이크로소프트가 접속 방식을 바꾸면 로그에 `edge_failed`가 찍히고, 앱은 폰 목소리로 읽습니다(끊기지 않음).
`requirements.txt`의 edge-tts 버전을 최신으로 올리고 다시 빌드하면 대개 고쳐집니다.
