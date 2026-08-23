FROM python:3.11-slim

WORKDIR /app

RUN useradd -m -u 10001 simulator

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY simulator.py .

ENV PYTHONUNBUFFERED=1

USER simulator

CMD ["python", "-u", "simulator.py"]
