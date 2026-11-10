FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py disease_labels.py ensemble.py leaf_validator.py .

EXPOSE 8000

# Pre-download the model at image build time (uncomment for faster cold start
# in production; left commented so the base image build stays fast here):
# RUN python -c "from transformers import pipeline; pipeline('image-classification', model='linkanjarad/mobilenet_v2_1.0_224-plant-disease-identification')"

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
