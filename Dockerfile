FROM eclipse-temurin:21-jdk
  WORKDIR /app
  COPY Enrollmentform.java index.html ./
  CMD ["java", "Enrollmentform.java"]
