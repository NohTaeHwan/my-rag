FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
# Node 단계의 dist를 bootJar가 static/으로 포함한다 — Java 단계에서 npm을 다시 돌리지 않는다(build.gradle).
COPY --from=frontend /frontend/dist ./frontend/dist
RUN ./gradlew bootJar --no-daemon -x test -PprebuiltFrontendDist=frontend/dist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
