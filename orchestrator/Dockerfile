FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py router.py weather.py .

ENV PORT=8080
ENV OLLAMA_R740=http://host.docker.internal:11434
ENV OLLAMA_DESKTOP=http://192.168.2.38:11434
ENV OLLAMA_R740_MODEL=qwen2.5:7b
ENV OLLAMA_DESKTOP_MODEL=qwen2.5:32b

EXPOSE 8080

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
