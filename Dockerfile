FROM maven:3.6-jdk-8-alpine as maven
ADD . /app/
WORKDIR /app/
RUN mvn package

FROM openjdk:8-jre-alpine as java
WORKDIR /app/
COPY --from=maven /app/target/notify-0.0.1-SNAPSHOT-jar-with-dependencies.jar /app/notify.jar
EXPOSE 8080
CMD ["java","-jar","notify.jar"]