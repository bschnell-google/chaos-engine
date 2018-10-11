FROM maven:3.5.4-jdk-8-alpine AS build-env
WORKDIR /chaosengine
ADD pom.xml /chaosengine
RUN mvn dependency:go-offline -Dsilent=true
ADD src /chaosengine/src
RUN cd /chaosengine && mvn clean test package

FROM openjdk:8-jre-alpine AS test
EXPOSE 8080
COPY --from=build-env /chaosengine/target/chaosengine.jar /
ENV DEPLOYMENT_ENVIRONMENT=DEVELOPMENT
LABEL com.datadoghq.ad.logs="[ { \"source\":\"java\", \"service\": \"chaosengine\" } ]"
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/chaosengine.jar"]

FROM test
ENV DEPLOYMENT_ENVIRONMENT=PROD