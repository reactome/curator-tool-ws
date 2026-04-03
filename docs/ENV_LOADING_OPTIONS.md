# .env Loading Options - What Actually Works

## ✅ Recommended: Manual Environment Loading (No Dependencies)

**Most reliable approach—works everywhere:**

```bash
# Load .env into current shell
set -a           # Mark all new variables for export
source .env      # Load variables from .env file
set +a           # Unset the marker

# Now run your app (variables are in environment)
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar
```

**Or in one command:**
```bash
(source .env && java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar)
```

**Add to `.bashrc` or `.zshrc` for convenience:**
```bash
alias curator-run='(source .env && java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar)'
alias curator-dev='(source .env && ./mvnw spring-boot:run)'
```

Then just use: `curator-run` or `curator-dev`

## ✅ JetBrains / IntelliJ IDEA: Best Way In The IDE

If you start the app from the IDE's **Run/Debug configuration**, `source .env` in your shell is **not** applied automatically to that run configuration.

The most reliable IDE-native setup is to put the required variables directly into the run configuration.

This project currently expects these variables:

```text
NEO4J_URI
NEO4J_USER
NEO4J_PASSWORD
DATASOURCE_URL
DATASOURCE_USER
DATASOURCE_PASSWORD
```

### Option A: Spring Boot/Application Run Configuration

1. Open **Run | Edit Configurations...**
2. Select your Spring Boot or Application configuration
3. Find **Environment variables**
4. Add values such as:

```text
NEO4J_URI=bolt://localhost:7687;NEO4J_USER=neo4j;NEO4J_PASSWORD=your-password;DATASOURCE_URL=jdbc:h2:file:./data/reactome_h2;DB_CLOSE_DELAY=-1;FILE_LOCK=NO;DATASOURCE_USER=reactome;DATASOURCE_PASSWORD=your-password
```

5. Apply and run

### Option B: Maven Run Configuration

If you run the app via `spring-boot:run` inside IntelliJ:

1. Open **Run | Edit Configurations...**
2. Create or select a **Maven** configuration
3. Set command line to:

```text
spring-boot:run
```

4. Put the same values into **Environment variables**
5. Apply and run

### Option C: IDE Terminal

If you use the integrated terminal instead of a run configuration, this works exactly like a normal shell:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

### Important Note On macOS

On macOS, IntelliJ started from the Dock/Finder often does **not** inherit shell variables from `~/.bashrc` or `~/.zshrc`. So even if `echo $NEO4J_PASSWORD` works in Terminal.app, the IDE run configuration may still not see it unless you define it inside the run configuration itself.

## ✅ Alternative: Java Command-Line Arguments (For CI/CD)

```bash
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar \
  --spring.neo4j.authentication.password=macneo4j \
  --spring.datasource.password=web_tool_h2_er
```

## ✅ Optional: dotenv-java Library (For Automatic Loading)

If you want automatic `.env` loading without manual sourcing:

**Add to `pom.xml`:**
```xml
<dependency>
    <groupId>io.github.cdimascio</groupId>
    <artifactId>dotenv-java</artifactId>
    <version>2.3.2</version>
</dependency>
```

**Create `src/main/java/org/reactome/curation/config/DotenvConfig.java`:**
```java
package org.reactome.curation.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Configuration;

/**
 * Loads environment variables from .env file on application startup.
 * This allows .env to be loaded automatically without manual 'source .env'.
 */
@Configuration
public class DotenvConfig {
    static {
        // Load .env file if it exists (ignores if missing for production)
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();
        
        // Copy all .env entries into System properties (which Spring reads)
        dotenv.entries().forEach(entry -> 
            System.setProperty(entry.getKey(), entry.getValue())
        );
    }
}
```

**Now you can run without `source .env`:**
```bash
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar
# .env is automatically loaded at startup
```

## ❌ What Does NOT Work

- ❌ `maven-dotenv-plugin` (doesn't exist)
- ❌ `spring-dotenv` plugin (doesn't exist)
- ❌ `me.paulschwarz:spring-dotenv` (doesn't exist)
- ❌ Maven plugins for .env loading (Java doesn't have standard ones)

Maven loads properties at **build time**, but Spring's `application.properties` is evaluated at **runtime**. For runtime secrets, use libraries or manual environment variables.

## Summary

| Use Case | Method | Command |
|----------|--------|---------|
| **Local development** | Manual source | `(source .env && ./mvnw spring-boot:run)` |
| **JetBrains run config** | IDE Environment variables | Set in Run/Debug configuration |
| **Shell alias** | Add to bashrc | `alias curator-dev='(source .env && ...'` |
| **CI/CD pipelines** | CLI arguments | `java -jar app.jar --spring.datasource.password=...` |
| **Automatic loading** | dotenv-java library | Add dependency + config class |
| **Production** | Environment variables | Set in deployment platform |

**Recommendation**: Use manual `source .env` for local dev (simplest), and environment variables for production (most secure).

