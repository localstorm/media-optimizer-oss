#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOCKER_DIR="${SCRIPT_DIR}/media-optimizer-docker"
PROJECT_DIR="${SCRIPT_DIR}"

# Load build properties
if [ ! -f "${SCRIPT_DIR}/build.properties" ]; then
    echo "ERROR: build.properties not found. Copy build.properties.template and fill in values." >&2
    exit 1
fi
source "${SCRIPT_DIR}/build.properties"

echo "==> Building media-optimizer Maven project..."
(cd "${PROJECT_DIR}" && mvn clean package -DskipTests)

echo "==> Preparing download directory..."
rm -rf "${DOCKER_DIR}/download"
mkdir -p "${DOCKER_DIR}/download"

echo "==> Copying server JAR..."
cp "${PROJECT_DIR}/media-optimizer-server/target/media-optimizer-server-0.1.0-SNAPSHOT.jar" \
    "${DOCKER_DIR}/download/media-optimizer-server-0.1.0-SNAPSHOT.jar"

echo "==> Retrieving credentials and API keys from Secrets Manager..."
SECRET_JSON=$(aws secretsmanager get-secret-value \
    --region "${AWS_REGION}" \
    --secret-id "${AWS_COMBINED_SECRET_NAME}" \
    --query SecretString \
    --output text)

AWS_ACCESS_KEY_ID=$(echo "${SECRET_JSON}" | jq -r '.AWS_ACCESS_KEY_ID')
AWS_SECRET_ACCESS_KEY=$(echo "${SECRET_JSON}" | jq -r '.AWS_SECRET_ACCESS_KEY')
YOUTUBE_API_KEY=$(echo "${SECRET_JSON}" | jq -r '.YOUTUBE_API_KEY')
ANTHROPIC_API_KEY=$(echo "${SECRET_JSON}" | jq -r '.ANTHROPIC_API_KEY')
GOOGLE_TTS_API_KEY=$(echo "${SECRET_JSON}" | jq -r '.GOOGLE_TTS_API_KEY')
TELEGRAM_API_CREDENTIALS=$(echo "${SECRET_JSON}" | jq -r '.TELEGRAM_API_CREDENTIALS')

echo "==> Generating credentials.sh..."
cat > "${DOCKER_DIR}/download/credentials.sh" <<CREDS
#!/usr/bin/env bash
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}"
export AWS_REGION="${AWS_REGION}"
export OPTIMIZER_S3_BUCKET="${OPTIMIZER_S3_BUCKET}"
export OPTIMIZER_S3_KEY="${OPTIMIZER_S3_KEY}"
export TZ="${TIMEZONE}"
export TEMPSPACE_DIR="/tempspace"
export MODELS_DIR="/models"
export YOUTUBE_API_KEY="${YOUTUBE_API_KEY}"
export ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY}"
export GOOGLE_TTS_API_KEY="${GOOGLE_TTS_API_KEY}"
export TELEGRAM_API_CREDENTIALS="${TELEGRAM_API_CREDENTIALS}"
CREDS

echo "==> Copying launch script..."
cp "${DOCKER_DIR}/scripts/launch.sh" "${DOCKER_DIR}/download/launch.sh"

echo "==> Checking tdl Telegram session..."
TDL_DATA_DIR="$HOME/.tdl/data"
if [ ! -d "$TDL_DATA_DIR" ] || [ -z "$(ls -A "$TDL_DATA_DIR" 2>/dev/null)" ]; then
    echo "    No tdl session found. Starting interactive login (scan QR with Telegram)..."
    tdl login -T qr
fi
echo "    Copying tdl session into build context..."
cp -r "$TDL_DATA_DIR" "${DOCKER_DIR}/download/tdl-data"

echo "==> Building Docker image..."
docker build -t media-optimizer:latest "${DOCKER_DIR}"

echo "==> Cleaning up download directory..."
rm -rf "${DOCKER_DIR}/download"

echo "==> Done. Image built as media-optimizer:latest"
