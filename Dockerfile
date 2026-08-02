# Step 1 — Start from a base image that has Java 21
FROM eclipse-temurin:21-jre-alpine

# Step 2 — Create a folder inside the container for our app
WORKDIR /app

# Step 3 — Copy our JAR file into the container
COPY build/libs/bookstore-api-0.0.1-SNAPSHOT.jar app.jar

# Step 4 — Tell Docker how to run our app
ENTRYPOINT ["java", "-jar", "app.jar"]