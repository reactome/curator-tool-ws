#!/bin/bash
# Quick setup script for local development
# This script prepares the environment to run the curator-tool-ws application

set -e

echo "🔧 Curator Tool WS - Local Development Setup"
echo "=============================================="
echo ""

# Check if .env exists
if [ ! -f .env ]; then
    echo "⚠️  .env file not found"
    echo "📋 Creating .env from .env.example..."
    cp .env.example .env
    echo "✅ Created .env"
    echo ""
    echo "⚠️  IMPORTANT: Edit .env with your actual database credentials:"
    echo "   nano .env"
    echo ""
else
    echo "✅ .env file already exists"
fi

# Source environment variables
echo "📦 Loading environment variables from .env..."
set -a
source .env
set +a
echo "✅ Environment variables loaded"
echo ""

# Verify required variables are set
echo "🔍 Verifying required environment variables..."
required_vars=("NEO4J_PASSWORD" "DATASOURCE_PASSWORD")
all_set=true

for var in "${required_vars[@]}"; do
    if [ -z "${!var}" ]; then
        echo "❌ Missing: $var"
        all_set=false
    else
        echo "✅ $var is set"
    fi
done

if [ "$all_set" = false ]; then
    echo ""
    echo "⚠️  Some environment variables are missing!"
    echo "Please update .env with your actual credentials."
    exit 1
fi

echo ""
echo "🚀 Setup complete!"
echo ""
echo "Next steps:"
echo "1. Build the application:"
echo "   ./mvnw clean package -DskipTests"
echo ""
echo "2. Run the application:"
echo "   java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-boot.jar"
echo ""
echo "3. Or use Maven directly:"
echo "   ./mvnw spring-boot:run"
echo ""
echo "For more information, see: PASSWORD_PROTECTION.md"

