FROM python:3.12-slim
WORKDIR /app
COPY deploy/server-requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY api.py agent.py configuration_tools.py voice_gateway.py realtime_agent.py /app/
ENV PYTHONUNBUFFERED=1
