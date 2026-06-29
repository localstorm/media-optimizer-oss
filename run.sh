#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOCKER_DIR="${SCRIPT_DIR}/media-optimizer-docker"

CONTAINER_NAME="media-optimizer"
IMAGE_NAME="media-optimizer:latest"
PORT="8080"

# Load build properties
source "${SCRIPT_DIR}/build.properties"

echo "==> Building image..."
bash "${SCRIPT_DIR}/build.sh"

echo "==> Stopping and removing existing container (if any)..."
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    docker stop "${CONTAINER_NAME}" || true
    docker rm "${CONTAINER_NAME}"
fi

echo "==> Preparing host directories..."
mkdir -p "${HOST_TEMPSPACE_DIR}"
mkdir -p "${HOST_MODELS_DIR}"

echo "==> Starting new container..."
docker run -d \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    -p "${PORT}:8080" \
    -v "${HOST_TEMPSPACE_DIR}:/tempspace" \
    -v "${HOST_MODELS_DIR}:/models" \
    "${IMAGE_NAME}"

echo "==> Container started. Logs:"
docker logs -f "${CONTAINER_NAME}"
