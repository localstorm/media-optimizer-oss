FROM amazoncorretto:21-al2023
LABEL maintainer="alexey@kuznetsov.co"

# Install dependencies: timezone data, ffmpeg, Python 3.12, yt-dlp, openai-whisper
RUN dnf install -y tzdata python3.12 python3.12-pip xz tar gzip unzip && dnf clean all \
    && alternatives --install /usr/bin/python3 python3 /usr/bin/python3.12 1 \
    && alternatives --install /usr/bin/pip3 pip3 /usr/bin/pip3.12 1

# Install static ffmpeg binary
RUN curl -L https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz \
    | tar -xJ --strip-components=1 -C /usr/local/bin --wildcards '*/ffmpeg' '*/ffprobe'

# Install CPU-only PyTorch first so openai-whisper doesn't pull CUDA wheels (~2 GB saved)
RUN pip3.12 install --no-cache-dir \
    torch torchvision torchaudio \
    --index-url https://download.pytorch.org/whl/cpu
RUN pip3.12 install --no-cache-dir openai-whisper

# Install yt-dlp nightly binary (pip package lags behind; nightly has SABR fix)
RUN curl -L https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp_linux \
    -o /usr/local/bin/yt-dlp && chmod +x /usr/local/bin/yt-dlp

# Install deno JS runtime (required by yt-dlp nightly for YouTube extraction)
RUN curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh

# Install tdl Telegram CLI
RUN curl -sSL https://github.com/iyear/tdl/releases/latest/download/tdl_Linux_64bit.tar.gz \
    | tar -xz -C /usr/local/bin tdl && chmod +x /usr/local/bin/tdl

ADD download/tdl-data /root/.tdl/data

ADD download/media-optimizer-server-0.1.0-SNAPSHOT.jar /app.jar
ADD download/credentials.sh /
ADD download/launch.sh /

RUN chmod +x /launch.sh /credentials.sh

RUN mkdir -p /tempspace /models && chmod 777 /tempspace /models

VOLUME ["/tempspace", "/models"]

EXPOSE 8080

CMD ["/launch.sh"]
