FROM gradle:8.14.5-jdk21 AS build
WORKDIR /workspace
COPY backend ./backend
COPY docs ./docs
WORKDIR /workspace/backend
RUN gradle --no-daemon --max-workers=1 bootJar -x test
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/fsp-talent-backend-0.1.0.jar /app/app.jar
COPY --chmod=0755 infra/run-api.sh /app/run-api.sh
EXPOSE 8000
ENTRYPOINT []
CMD ["/app/run-api.sh"]
