# Этап 1: Сборка приложения
FROM maven:3.9.9-eclipse-temurin-21 AS builder

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем только pom.xml для кэширования зависимостей
COPY pom.xml .

# Загружаем зависимости
RUN mvn dependency:go-offline -B

# Копируем исходный код
COPY src ./src

# Собираем приложение, пропуская тесты
RUN mvn clean package -DskipTests


# Этап 2: Создание финального образа
FROM eclipse-temurin:21-jre

# Устанавливаем рабочую директорию
WORKDIR /app

# Создаём пользователя
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

# Переключаемся на пользователя
USER appuser

# Копируем JAR с правильными правами
COPY --from=builder /app/target/edge-service-*.jar app.jar

# Открываем порт
EXPOSE 8080

# Оптимизируем JVM для контейнеров
ENV JAVA_OPTS="-Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC"

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Запускаем приложение
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]