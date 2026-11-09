FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY . .
CMD ["java", "Enrollmentform.java"]
