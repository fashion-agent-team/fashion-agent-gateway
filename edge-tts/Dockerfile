# Edge TTS 사이드카. 순수 파이썬이라 젯슨(arm64)·맥(arm64)·x86 모두 같은 파일로 빌드됩니다.
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py .
ENV TZ=Asia/Seoul PYTHONUNBUFFERED=1 PORT=8099
EXPOSE 8099
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:8099/health',timeout=2)" || exit 1
USER nobody
CMD ["python", "server.py"]
