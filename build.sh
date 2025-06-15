#!/bin/bash
set -e

# Directories and jar names for server and client.
SERVER_SRC="MusicServer/src"
SERVER_LIB="MusicServer/lib"
SERVER_TARGET="MusicServer/target"
SERVER_JAR="$SERVER_TARGET/MusicServer.jar"

CLIENT_SRC="MusicClient/src"
CLIENT_LIB="MusicClient/lib"
CLIENT_TARGET="MusicClient/target"
CLIENT_JAR="$CLIENT_TARGET/MusicClient.jar"

echo "=== Cleaning previous builds ==="
rm -rf "$SERVER_TARGET" "$CLIENT_TARGET"
mkdir -p "$SERVER_TARGET" "$CLIENT_TARGET"

echo "=== Compiling MusicServer ==="
# Compile all Java files in the MusicServer src directory.
javac -d "$SERVER_TARGET" -cp "$SERVER_LIB/gson.jar:$SERVER_LIB/jaudiotagger.jar" $(find "$SERVER_SRC" -name "*.java")
echo "MusicServer compiled successfully."

echo "=== Packaging MusicServer into JAR with manifest ==="
# Create a manifest file for the MusicServer jar.
MANIFEST_SERVER="$SERVER_TARGET/manifest.txt"
echo "Manifest-Version: 1.0" > "$MANIFEST_SERVER"
echo "Main-Class: MusicServer" >> "$MANIFEST_SERVER"
# Note: The class-path is relative to the location of the jar file.
echo "Class-Path: ../lib/gson.jar ../lib/jaudiotagger.jar" >> "$MANIFEST_SERVER"
# Package the jar using the manifest.
pushd "$SERVER_TARGET" > /dev/null
jar cfm "MusicServer.jar" manifest.txt $(find . -name "*.class")
popd > /dev/null

echo "=== Compiling MusicClient ==="
# Compile all Java files in the MusicClient src directory.
javac -d "$CLIENT_TARGET" -cp "$CLIENT_LIB/gson.jar" $(find "$CLIENT_SRC" -name "*.java")
echo "MusicClient compiled successfully."

echo "=== Packaging MusicClient into JAR with manifest ==="
# Create a manifest file for the MusicClient jar.
MANIFEST_CLIENT="$CLIENT_TARGET/manifest.txt"
echo "Manifest-Version: 1.0" > "$MANIFEST_CLIENT"
echo "Main-Class: MusicClient" >> "$MANIFEST_CLIENT"
echo "Class-Path: ../lib/gson.jar" >> "$MANIFEST_CLIENT"
# Package the jar using the manifest.
pushd "$CLIENT_TARGET" > /dev/null
jar cfm "MusicClient.jar" manifest.txt $(find . -name "*.class")
popd > /dev/null

echo "=== Build Process Complete ==="
echo "Server jar created at: $SERVER_JAR"
echo "Client jar created at: $CLIENT_JAR"
