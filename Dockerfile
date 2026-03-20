FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src
COPY scripts ./scripts

RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update -y \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       ocrmypdf \
       tesseract-ocr \
       tesseract-ocr-chi-sim \
       poppler-utils \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target ./target
COPY --from=build /app/scripts ./scripts
COPY seed ./seed

RUN chmod +x /app/scripts/start-render.sh

EXPOSE 10000
CMD ["./scripts/start-render.sh"]
