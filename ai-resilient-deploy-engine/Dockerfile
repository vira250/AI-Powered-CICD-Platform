# Production Multi-Stage Dockerfile for AI Deployment & Resiliency Engine
FROM python:3.14-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8080

WORKDIR /app

# Install system utilities
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install Python requirements
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application codebase
COPY . .

# Expose ports for Controller (8080) and Mock Production (8081)
EXPOSE 8080 8081

# Run the unified deployment controller engine
CMD ["python", "deployment_engine.py", "--port", "8080"]
