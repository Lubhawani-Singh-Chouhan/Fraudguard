FROM python:3.11-slim

WORKDIR /app

RUN useradd -m -u 10001 mcp

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY *.py ./
COPY sql ./sql

ENV PYTHONUNBUFFERED=1
EXPOSE 8090

USER mcp

CMD ["python", "server.py"]
