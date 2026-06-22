# Production image for the analitica web server.
# Builds the ClojureScript SPA in-image so deploys do not depend on local
# resources/public/js/cljs-out artifacts.
FROM clojure:temurin-21-tools-deps-alpine AS cljs-build

WORKDIR /build

RUN apk add --no-cache nodejs npm

COPY deps.edn shadow-cljs.edn package.json package-lock.json ./
RUN npm ci && clojure -P -M:cljs

COPY src ./src
COPY resources ./resources
RUN npm run release

FROM clojure:temurin-21-tools-deps-alpine

WORKDIR /app

RUN apk add --no-cache curl

# Cache deps separately from source so changes to .clj don't re-download Maven jars.
COPY deps.edn ./
RUN clojure -P

# Copy source + resources. config.edn / .env / *.db are mounted at runtime
# via docker-compose volumes.
COPY src ./src
COPY --from=cljs-build /build/resources ./resources

# Web UI port (web/server.clj :port default 3000; can be overridden with --port=NNNN).
EXPOSE 3000

# Runtime env points the SQLite path at the volume mount under /app/data.
ENV ANALITICA_DB=/app/data/analitica.db

# JVM defaults — encoding for Russian source data, modest heap for 4 GB host.
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Xmx2g -XX:+ExitOnOutOfMemoryError"

CMD ["clojure", "-M", "-m", "analitica.web.server"]
