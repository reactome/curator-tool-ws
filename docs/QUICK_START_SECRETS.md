# Quick Start: Local Development with Secrets Protected

## One-Time Setup

```bash
# Run setup script to create .env file
./setup-dev.sh

# Edit .env with your actual database passwords
nano .env
```

## Running the Application

### Option 1: Using Setup Script (Recommended)

```bash
# Setup loads environment and shows next steps
./setup-dev.sh
```

### Option 2: Manual Environment Loading (Simple & Works Everywhere)

```bash
# Load environment variables from .env
set -a
source .env
set +a

# Build and run
./mvnw clean package -DskipTests
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar
```

Or in one line:
```bash
(source .env && ./mvnw spring-boot:run)
```

### Option 3: Using Java Command-Line Arguments

```bash
# Pass secrets directly to Java (useful for CI/CD)
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar \
  --spring.neo4j.authentication.password=macneo4j \
  --spring.datasource.password=web_tool_h2_er
```

### Option 4: Docker

```bash
# Build
docker build -t curator-tool-ws .

# Run with environment variables
docker run \
  -e NEO4J_PASSWORD=macneo4j \
  -e DATASOURCE_PASSWORD=web_tool_h2_er \
  -p 9090:9090 \
  curator-tool-ws
```

### Option 5: JetBrains / IntelliJ Run Configuration

If you run from the IDE, the simplest reliable setup is:

1. Open **Run | Edit Configurations...**
2. Open your **Spring Boot**, **Application**, or **Maven** run configuration
3. In **Environment variables**, add:

```text
NEO4J_URI=bolt://localhost:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=your-password
DATASOURCE_URL=jdbc:h2:file:./data/reactome_h2;DB_CLOSE_DELAY=-1;FILE_LOCK=NO
DATASOURCE_USER=reactome
DATASOURCE_PASSWORD=your-password
```

Then run normally from the IDE.

> Note: `source .env` in a shell does not automatically populate a JetBrains Run Configuration.

## Important Files

| File | Purpose | Committed? |
|------|---------|-----------|
| `.env` | **Real secrets** (local dev only) | ❌ NO - in .gitignore |
| `.env.example` | Template with no real values | ✅ YES - safe to commit |
| `PASSWORD_PROTECTION.md` | Detailed security documentation | ✅ YES |
| `setup-dev.sh` | Automated setup script | ✅ YES |

## Security Checklist

- ✅ Passwords moved from `application.properties` to environment variables
- ✅ `.env` file with real passwords in `.gitignore`
- ✅ `.env.example` provides safe template
- ✅ Default values are non-functional
- ✅ Build verified with environment variables

## Troubleshooting

**Problem**: "Cannot connect to Neo4j"
- Solution: Verify `NEO4J_PASSWORD` is set: `echo $NEO4J_PASSWORD`

**Problem**: "H2 database authentication failed"
- Solution: Verify `DATASOURCE_PASSWORD` is set: `echo $DATASOURCE_PASSWORD`

**Problem**: "Environment variables not loaded"
- Solution: Run `source .env` before commands

**Problem**: "Works in terminal, not in IntelliJ"
- Solution: Add the variables to the IDE Run Configuration directly
- On macOS, GUI apps often do not inherit shell exports from `~/.bashrc`/`~/.zshrc`

For detailed information, see `PASSWORD_PROTECTION.md`

