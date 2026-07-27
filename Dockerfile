FROM eclipse-temurin:17-jre-jammy

WORKDIR /r0re
COPY build/libs/r0re-*.jar /r0re/r0re.jar

ENV R0RE_DATA_DIR=/root/.local/share/r0re
EXPOSE 8000

ENTRYPOINT ["java", "-jar", "/r0re/r0re.jar"]
