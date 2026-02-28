#!/bin/bash
# Build Promotion Script
# Promotes staging build to production after user confirms stability
# Usage: ./promote_build.sh [version]

set -e  # Exit on error

BASE_DIR="$HOME/mobileKinetic"
PRODUCTION_DIR="$BASE_DIR/production"
STAGING_DIR="$BASE_DIR/staging"
BACKUP_DIR="$BASE_DIR/backups"
LOG_FILE="$BASE_DIR/build_history.log"

TIMESTAMP=$(date +%s)
VERSION="${1:-$(date +%Y%m%d.%H%M%S)}"

echo "==================================="
echo "Build Promotion Script"
echo "==================================="
echo "Timestamp: $TIMESTAMP"
echo "Version: $VERSION"
echo ""

# Step 1: Backup current production
echo "[1/6] Backing up current production..."
BACKUP_PATH="$BACKUP_DIR/production.$TIMESTAMP"
mkdir -p "$BACKUP_PATH"
cp -r "$PRODUCTION_DIR"/* "$BACKUP_PATH/" 2>/dev/null || echo "Production directory empty or doesn't exist"

BACKUP_FILES=$(find "$BACKUP_PATH" -type f | wc -l)
echo "  Backed up $BACKUP_FILES files to $BACKUP_PATH"

# Step 2: Log backup
echo "[$TIMESTAMP] Backup created: $BACKUP_PATH (Version: $VERSION)" >> "$LOG_FILE"

# Step 3: Promote staging to production
echo "[2/6] Promoting staging to production..."
rm -rf "$PRODUCTION_DIR"
mkdir -p "$PRODUCTION_DIR"
cp -r "$STAGING_DIR"/* "$PRODUCTION_DIR/"

PROD_FILES=$(find "$PRODUCTION_DIR" -type f | wc -l)
echo "  Promoted $PROD_FILES files to production"

# Step 4: Update version file
echo "[3/6] Updating version metadata..."
echo "$VERSION" > "$PRODUCTION_DIR/VERSION"
echo "Build: $VERSION" > "$BASE_DIR/CURRENT_VERSION"
echo "Timestamp: $TIMESTAMP" >> "$BASE_DIR/CURRENT_VERSION"
echo "Promoted: $(date)" >> "$BASE_DIR/CURRENT_VERSION"

# Step 5: Reset staging
echo "[4/6] Resetting staging environment..."
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"
cp -r "$PRODUCTION_DIR"/* "$STAGING_DIR/"

# Clear build artifacts
rm -rf "$STAGING_DIR/build"
rm -rf "$STAGING_DIR/app/build"

STAGING_FILES=$(find "$STAGING_DIR" -type f | wc -l)
echo "  Reset staging with $STAGING_FILES files"

# Step 6: Log promotion
echo "[5/6] Logging promotion..."
echo "[$TIMESTAMP] PROMOTION: Version $VERSION promoted to production" >> "$LOG_FILE"
echo "[$TIMESTAMP]   Backup: $BACKUP_PATH" >> "$LOG_FILE"
echo "[$TIMESTAMP]   Files: $PROD_FILES" >> "$LOG_FILE"

# Step 7: Cleanup old backups (keep last 5)
echo "[6/6] Cleaning up old backups..."
BACKUP_COUNT=$(ls -1d "$BACKUP_DIR"/production.* 2>/dev/null | wc -l)
if [ "$BACKUP_COUNT" -gt 5 ]; then
    ls -1dt "$BACKUP_DIR"/production.* | tail -n +6 | xargs rm -rf
    REMOVED=$((BACKUP_COUNT - 5))
    echo "  Removed $REMOVED old backup(s)"
else
    echo "  Keeping all $BACKUP_COUNT backup(s)"
fi

echo ""
echo "==================================="
echo "Promotion Complete!"
echo "==================================="
echo "Production: $PRODUCTION_DIR"
echo "Staging:    $STAGING_DIR"
echo "Backup:     $BACKUP_PATH"
echo "Version:    $VERSION"
echo ""
echo "Next steps:"
echo "  - Edit files in staging/"
echo "  - Build: cd staging && ./gradlew assembleDebug"
echo "  - Test and confirm stability"
echo "  - Run this script again to promote"
