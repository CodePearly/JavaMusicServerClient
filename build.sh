#!/bin/bash
set -e

#############################
# Build MusicServer Section #
#############################

SERVER_SRC="MusicServer/src"
SERVER_LIB="MusicServer/lib"
SERVER_TARGET="MusicServer/target"
SERVER_JAR="$SERVER_TARGET/MusicServer.jar"

echo "=== Cleaning previous MusicServer builds ==="
rm -rf "$SERVER_TARGET"
mkdir -p "$SERVER_TARGET"

echo "=== Compiling MusicServer ==="
javac -d "$SERVER_TARGET" -cp "$SERVER_LIB/gson.jar:$SERVER_LIB/jaudiotagger.jar" $(find "$SERVER_SRC" -name "*.java")
echo "MusicServer compiled successfully."

echo "=== Packaging MusicServer into JAR with manifest ==="
MANIFEST_SERVER="$SERVER_TARGET/manifest.txt"
{
  echo "Manifest-Version: 1.0"
  echo "Main-Class: MusicServer"
  # The class-path is relative to MusicServer/target
  echo "Class-Path: ../lib/gson.jar ../lib/jaudiotagger.jar"
} > "$MANIFEST_SERVER"
pushd "$SERVER_TARGET" > /dev/null
jar cfm "MusicServer.jar" manifest.txt $(find . -name "*.class")
popd > /dev/null

#############################
# Build MusicClient Section #
#############################

CLIENT_SRC="MusicClient/src"
CLIENT_LIB="MusicClient/lib"
CLIENT_TARGET="MusicClient/target"
CLIENT_JAR="$CLIENT_TARGET/MusicClient.jar"

echo "=== Cleaning previous MusicClient builds ==="
rm -rf "$CLIENT_TARGET"
mkdir -p "$CLIENT_TARGET"

echo "=== Compiling MusicClient ==="
# Compile all .java files under MusicClient/src (MusicClient.java & AudioPlayerApp.java)
javac -d "$CLIENT_TARGET" -cp "$CLIENT_LIB/gson.jar:$CLIENT_LIB/jlayer.jar:$CLIENT_LIB/javafx-base.jar:$CLIENT_LIB/javafx-graphics.jar:$CLIENT_LIB/javafx-media.jar" $(find "$CLIENT_SRC" -name "*.java")
echo "MusicClient compiled successfully."

echo "=== Packaging MusicClient into JAR with manifest ==="
MANIFEST_CLIENT="$CLIENT_TARGET/manifest.txt"
{
  echo "Manifest-Version: 1.0"
  echo "Main-Class: MusicClient"
  # Class-Path relative to MusicClient/target:
  # Add gson, jlayer, and the three JavaFX jars.
  echo "Class-Path: ../lib/gson.jar ../lib/jlayer.jar ../lib/javafx-base.jar ../lib/javafx-graphics.jar ../lib/javafx-media.jar"
} > "$MANIFEST_CLIENT"
pushd "$CLIENT_TARGET" > /dev/null
jar cfm "MusicClient.jar" manifest.txt $(find . -name "*.class")
popd > /dev/null

echo "=== Build Process Complete ==="
echo "Server jar created at: $SERVER_JAR"
echo "Client jar created at: $CLIENT_JAR"
