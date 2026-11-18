FROM node:24-alpine AS frontend
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src
COPY backend/ ./
RUN ./gradlew installDist --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home guardadoc
WORKDIR /app
COPY --from=backend /src/build/install/guardadoc-backend/ ./
COPY --from=frontend /src/dist/ ./static/
RUN mkdir -p data/storage && chown -R guardadoc data
USER guardadoc
ENV STATIC_DIR=/app/static STORAGE_DIR=/app/data/storage PORT=7070
EXPOSE 7070
ENTRYPOINT ["./bin/guardadoc-backend"]
