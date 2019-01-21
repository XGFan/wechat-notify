FROM maven:3.6-jdk-8-alpine
ADD . /app/
WORKDIR /app/
EXPOSE 8080
CMD ["mvn","exec:java" ,"-Dexec.mainClass=com.test4x.app.notify.NotifyApplicationKt"]