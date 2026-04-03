# Password Protection & Secret Management

## Overview

This application stores sensitive credentials (database passwords) using **externalized configuration** via environment variables. Passwords are **not** hardcoded in version-controlled files.

## Current Implementation

### 1. Environment Variables with Fallback Defaults

All sensitive credentials in `application.properties` use Spring's placeholder syntax with fallbacks:

```properties
spring.neo4j.authentication.password=${NEO4J_PASSWORD:changeme}
spring.datasource.password=${DATASOURCE_PASSWORD:changeme}
```

- `${ENV_VAR_NAME:default_value}` syntax
- Reads from environment variable if set
- Falls back to `default_value` if not set
- Default values are non-functional passwords for local dev safety

### 2. Environment Variables to Set

**For Local Development**, create a `.env` file (not committed to git):

```bash
# Neo4j
NEO4J_PASSWORD=macneo4j

# H2 Database
DATASOURCE_PASSWORD=web_tool_h2_er
```

**For Production**, set environment variables in deployment platform:

```bash
# Linux/macOS/Docker
export NEO4J_PASSWORD=your-secure-password
export DATASOURCE_PASSWORD=your-secure-password

# Or pass directly to Java
java -jar app.jar --spring.neo4j.authentication.password=your-password
```

### 3. Loading `.env` Files

#### Option A: Manual Loading (Recommended for Local Dev)

```bash
# Load .env file before running app
set -a
source .env
set +a

# Run with environment variables loaded
./mvnw spring-boot:run
```

Or use a bash alias in your `.bashrc`/`.zshrc`:

```bash
alias curator-dev='set -a; source .env; set +a; cd /path/to/curator-tool-ws && ./mvnw spring-boot:run'
```

#### Option B: Add `dotenv-java` Library (Automatic at Runtime)

If you want automatic `.env` loading in the application, add this dependency to `pom.xml`:

```xml
<dependency>
    <groupId>io.github.cdimascio</groupId>
    <artifactId>dotenv-java</artifactId>
    <version>2.3.2</version>
</dependency>
```

Then create a Spring component to load it on startup:

```java
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DotenvConfig {
    static {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();
        dotenv.entries().forEach(entry -> 
            System.setProperty(entry.getKey(), entry.getValue())
        );
    }
}
```

This automatically loads `.env` when the application starts (if file exists).

#### Option C: Java System Properties (Alternative)

Pass via command line:

```bash
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar \
  --spring.neo4j.authentication.password=macneo4j \
  --spring.datasource.password=web_tool_h2_er
```

## Security Best Practices

### ✅ DO:
- ✅ Store `.env` locally and **never commit** to git
- ✅ Use `.env.example` to document required variables
- ✅ Use environment variables for all sensitive data
- ✅ Keep default values non-functional for safety
- ✅ Rotate credentials regularly in production
- ✅ Use deployment platform secrets management (AWS Secrets Manager, Azure Key Vault, etc.)

### ❌ DON'T:
- ❌ Commit `.env` files with real passwords
- ❌ Hardcode passwords in properties files
- ❌ Share passwords in chat, email, or logs
- ❌ Use default passwords in production
- ❌ Store secrets in application code
- ❌ Log sensitive values

## File Structure

```
curator-tool-ws/
├── .env                          ← LOCAL SECRETS (git-ignored, not committed)
├── .env.example                  ← TEMPLATE (safe to commit, no real values)
├── .gitignore                    ← Prevents .env commit
└── src/main/resources/
    └── application.properties    ← Uses ${ENV_VAR:default} placeholders
```

## Deployment Scenarios

### Local Development
```bash
# Copy example to real file
cp .env.example .env

# Edit .env with your local credentials
nano .env

# Load and run
set -a; source .env; set +a
./mvnw spring-boot:run
```

### JetBrains / IntelliJ IDEA

If you run the application from IntelliJ instead of a shell, configure the variables in the IDE run configuration:

1. Open **Run | Edit Configurations...**
2. Select your **Spring Boot**, **Application**, or **Maven** configuration
3. In **Environment variables**, define:

```text
NEO4J_URI=bolt://localhost:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=your-password
DATASOURCE_URL=jdbc:h2:file:./data/reactome_h2;DB_CLOSE_DELAY=-1;FILE_LOCK=NO
DATASOURCE_USER=reactome
DATASOURCE_PASSWORD=your-password
```

4. Apply and run

If you use the IDE terminal instead, load `.env` there first:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

On macOS, this distinction matters: IntelliJ launched from the Dock/Finder often does not inherit shell variables from `~/.bashrc` or `~/.zshrc`.

### Docker Deployment
```dockerfile
FROM openjdk:11-jre
ARG NEO4J_PASSWORD
ARG DATASOURCE_PASSWORD
ENV NEO4J_PASSWORD=${NEO4J_PASSWORD}
ENV DATASOURCE_PASSWORD=${DATASOURCE_PASSWORD}
COPY target/curator-tool-ws-*-boot.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Run with:
```bash
docker build \
  --build-arg NEO4J_PASSWORD=macneo4j \
  --build-arg DATASOURCE_PASSWORD=web_tool_h2_er \
  -t curator-tool-ws .

docker run \
  -e NEO4J_PASSWORD=macneo4j \
  -e DATASOURCE_PASSWORD=web_tool_h2_er \
  curator-tool-ws
```

### Kubernetes Deployment
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: curator-secrets
type: Opaque
stringData:
  neo4j-password: macneo4j
  datasource-password: web_tool_h2_er
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: curator-tool-ws
spec:
  containers:
  - name: curator
    image: curator-tool-ws:latest
    env:
    - name: NEO4J_PASSWORD
      valueFrom:
        secretKeyRef:
          name: curator-secrets
          key: neo4j-password
    - name: DATASOURCE_PASSWORD
      valueFrom:
        secretKeyRef:
          name: curator-secrets
          key: datasource-password
```

### CI/CD Pipeline (GitHub Actions)

Store secrets in GitHub repository settings, then use:

```yaml
name: Deploy
on: [push]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    - name: Build and Deploy
      env:
        NEO4J_PASSWORD: ${{ secrets.NEO4J_PASSWORD }}
        DATASOURCE_PASSWORD: ${{ secrets.DATASOURCE_PASSWORD }}
      run: |
        ./mvnw clean package -DskipTests
        java -jar target/curator-tool-ws-*-boot.jar
```

## Advanced Options (Future)

If you need more sophisticated secret management:

### 1. Spring Cloud Config Server
- Centralized configuration management
- Encryption support
- Real-time config updates without restart

### 2. HashiCorp Vault
- Enterprise secret management
- Automatic credential rotation
- Audit trails

### 3. AWS Secrets Manager / Azure Key Vault
- Cloud-native secret storage
- IAM integration
- Encryption at rest

### 4. Spring Cloud Sensitive Properties
- Masking in logs
- Encryption annotations

## Verification

### Check Current Configuration

```bash
# See which properties are loaded from environment
./mvnw spring-boot:run -Dspring-boot.run.arguments="--debug" 2>&1 | grep -i "password\|credential"
```

### Verify Secrets Are Not Logged

```bash
# Check logs don't contain passwords
grep -i "password\|secret" logs/*.log
```

### Scan for Hardcoded Secrets

```bash
# Search codebase for password patterns
grep -r "password\s*=" src/ --include="*.java" --include="*.properties"
```

## Troubleshooting

### "Authentication failed" error
- ✅ Verify environment variables are set: `echo $NEO4J_PASSWORD`
- ✅ Check `.env` file exists and is readable
- ✅ Reload shell after setting env vars: `source .env`
- ✅ Try passing password directly to test: `--spring.neo4j.authentication.password=test-value`

### "Cannot find .env file"
- ✅ Create it from template: `cp .env.example .env`
- ✅ Ensure it's in project root, not in subdirectory
- ✅ Check file permissions: `chmod 600 .env`

### Password visible in logs
- ✅ Set log level for credentials to ERROR only
- ✅ Configure Spring Security logging in `application.properties`:
  ```properties
  logging.level.org.springframework.security=ERROR
  logging.level.org.springframework.data.neo4j=ERROR
  ```

## Summary

| Environment | Method | Example |
|-------------|--------|---------|
| **Local Dev** | `.env` file | `cp .env.example .env; nano .env` |
| **Docker** | Build args + env vars | `docker run -e NEO4J_PASSWORD=...` |
| **Kubernetes** | Secrets object | `valueFrom.secretKeyRef` |
| **CI/CD** | Platform secrets | `${{ secrets.SECRET_NAME }}` |
| **Production** | Vault/Secrets Manager | Cloud-native integration |

All passwords are now **externalized, environment-based, and safely excluded from version control**.

