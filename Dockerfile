# Production image for the analitica web server.
# Built from a Clojure CLI base — no system JDK install needed on the host.
FROM clojure:temurin-21-tools-deps-bookworm-slim

WORKDIR /app

# Cache deps separately from source so changes to .clj don't re-download Maven jars.
COPY deps.edn ./
RUN clojure -P -X:deps prep

# Copy source + resources tracked by git (config.edn / .env / *.db
# are mounted at runtime via docker-compose volumes — see compose.yml).
COPY src ./src
COPY resources ./resources
COPY 1c ./1c

# Web UI port (web/server.clj :port default 3000; can be overridden with --port=NNNN).
EXPOSE 3000

# Runtime env points the SQLite path at the volume mount under /app/data.
ENV ANALITICA_DB=/app/data/analitica.db

# JVM defaults — encoding for Russian source data, modest heap for 4 GB host.
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Xmx2g -XX:+ExitOnOutOfMemoryError"

CMD ["clojure", "-M", "-m", "analitica.web.server"]
