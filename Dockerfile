# Use OpenJDK 23
FROM openjdk:23-slim

# Set working directory
WORKDIR /app

# Install curl
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Copy the application files
COPY build/libs/httpserver.jar .
COPY build/libs/httpserver-test.jar .

RUN mkdir -p /app/fileserver

# Expose the port your application runs on
EXPOSE 8080

# Command to run the application
# Replace "YourApplication.jar" with your actual JAR file name
CMD ["java", "-cp", "httpserver-test.jar:httpserver.jar", "SimpleFileServer","fileserver","8080","fileserver/logfile.txt" ]