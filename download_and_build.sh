#!/bin/bash
set -e

echo "=== Downloading External Libraries ==="

# Create the lib folders if they don't exist
mkdir -p MusicServer/lib MusicClient/lib

# Download Gson library for MusicServer and MusicClient.
# Using Maven Central URL for Gson version 2.10.1.
GSON_URL="https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
GSON_DEST_MS="MusicServer/lib/gson.jar"
GSON_DEST_MC="MusicClient/lib/gson.jar"

echo "Downloading Gson..."
if [ ! -f "$GSON_DEST_MS" ]; then
    curl -L -o "$GSON_DEST_MS" "$GSON_URL"
    echo "Gson downloaded to MusicServer/lib/gson.jar"
else
    echo "Gson already exists in MusicServer/lib."
fi

# For MusicClient, simply copy the downloaded Gson
if [ ! -f "$GSON_DEST_MC" ]; then
    cp "$GSON_DEST_MS" "$GSON_DEST_MC"
    echo "Gson copied to MusicClient/lib/gson.jar"
else
    echo "Gson already exists in MusicClient/lib."
fi

# Download Jaudiotagger for MusicServer.
# Using the corrected Maven Central URL for Jaudiotagger version 2.0.1.
JAUDIOTAGGER_URL="https://repo1.maven.org/maven2/org/jaudiotagger/jaudiotagger/2.0.1/jaudiotagger-2.0.1.jar"
JAUDIOTAGGER_DEST="MusicServer/lib/jaudiotagger.jar"

echo "Downloading Jaudiotagger..."
if [ ! -f "$JAUDIOTAGGER_DEST" ]; then
    curl -L -o "$JAUDIOTAGGER_DEST" "$JAUDIOTAGGER_URL"
    echo "Jaudiotagger downloaded to MusicServer/lib/jaudiotagger.jar"
else
    echo "Jaudiotagger already exists in MusicServer/lib."
fi

# Validate that jaudiotagger.jar is a valid JAR (by checking for the ZIP file signature "PK\x03\x04")
fileHeader=$(head -c 4 "$JAUDIOTAGGER_DEST")
if [[ "$fileHeader" != "PK"* ]]; then
    echo "Error: Downloaded jaudiotagger.jar does not appear to be a valid JAR (ZIP archive)."
    rm "$JAUDIOTAGGER_DEST"
    exit 1
fi

echo "=== External Libraries Downloaded and Verified ==="

echo "=== Running Build Script ==="
./build.sh
