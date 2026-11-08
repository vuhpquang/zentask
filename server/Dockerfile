# Runtime-only image. The server is built on the Mac (scripts/deploy.sh runs :server:installDist)
# because vps1 has ~2 GB RAM, not enough for a Gradle/Kotlin build.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY server/build/install/server /app/server
# Inside the container the server listens on all interfaces; compose publishes it on 127.0.0.1 only.
ENV PORT=3100 DATA_DIR=/app/data HOST=0.0.0.0
RUN mkdir -p /app/data
EXPOSE 3100
CMD ["/app/server/bin/server"]
