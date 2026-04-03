# File Backup Feature Implementation Summary

## Overview
Added automatic file backup functionality to `CurationFileRepository` that creates timestamped backups before persisting files, with configurable maximum backup count.

---

## Changes Made

### 1. **application.properties**
Added configuration property:
```properties
# File backup configuration - maximum number of backup files to keep
file.backup.max-count=25
```

**Default Value:** 25 backups

---

### 2. **CurationFileRepository.java**

#### Added Imports:
```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
```

#### Added Configuration Field:
```java
@Value("${file.backup.max-count:25}")
private int maxBackupCount;
```

#### Updated `persist()` Method:
Now checks if file exists and calls `backupFile()` before saving:
```java
if (file.exists()) {
    backupFile(fileName);
}
```

#### New Method: `backupFile(String fileName)`
**Purpose:** Creates a timestamped backup of the file

**Features:**
- Creates backup filename with format: `originalName_backup_yyyyMMdd_HHmmss.extension`
- Uses `Files.copy()` with `StandardCopyOption.REPLACE_EXISTING`
- Logs backup creation
- Automatically calls `cleanupOldBackups()` after creating backup

**Example:**
- Original: `userInstances.json`
- Backup: `userInstances_backup_20260305_143022.json`

#### New Method: `cleanupOldBackups(File originalFile)`
**Purpose:** Manages backup file count by deleting oldest backups

**Features:**
- Finds all backup files matching the pattern `baseName_backup_*`
- Only deletes if backup count exceeds `maxBackupCount`
- Sorts by file modification time (keeps newest files)
- Logs deletion of old backups

**Logic:**
1. Get all backup files for the base filename
2. If count ≤ maxBackupCount → do nothing
3. If count > maxBackupCount → delete oldest files
4. Keep only the most recent `maxBackupCount` backups

---

## How It Works

### Workflow:
```
persist() called
    ↓
Check if file exists
    ↓ (yes)
backupFile()
    ↓
Create timestamped copy
    ↓
cleanupOldBackups()
    ↓
    ├─ Count backups
    ├─ If > 25: Delete oldest
    └─ Keep newest 25
    ↓
Save new file
```

### Example Scenario:
```
Directory Before:
├── userInstances.json
├── userInstances_backup_20260301_100000.json
├── userInstances_backup_20260302_110000.json
├── ... (23 more backups)
└── userInstances_backup_20260304_120000.json  (25 backups total)

persist() called:
├── Create: userInstances_backup_20260305_143022.json
├── Total backups: 26
└── Delete: userInstances_backup_20260301_100000.json (oldest)

Directory After:
├── userInstances.json (updated)
├── userInstances_backup_20260302_110000.json
├── ... (23 backups)
├── userInstances_backup_20260304_120000.json
└── userInstances_backup_20260305_143022.json  (25 backups total)
```

---

## Configuration

To change the maximum number of backups, edit `application.properties`:

```properties
# Keep 50 backups instead of 25
file.backup.max-count=50

# Keep only 10 backups
file.backup.max-count=10
```

**Note:** If the property is not set, the default value of 25 is used via `@Value("${file.backup.max-count:25}")`

---

## Benefits

✅ **Automatic Backups** - No manual intervention needed
✅ **Timestamped** - Easy to identify when backup was created
✅ **Configurable** - Adjust max count via properties file
✅ **Space Management** - Automatically deletes old backups
✅ **Safe** - Original file only overwritten after successful backup
✅ **Logging** - All backup operations logged for auditing

---

## Error Handling

- **IOException** - If backup fails, exception is thrown and persist() is aborted
- **File Not Found** - If file doesn't exist, backup is skipped (no error)
- **Delete Failure** - Logged as warning, doesn't stop the process

---

## Implementation Complete ✅

All code compiles successfully with no errors!
