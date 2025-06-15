#!/bin/bash
set -e

echo "=== Downloading External Libraries ==="

# Ensure library directories exist for both projects.
mkdir -p MusicServer/lib MusicClient/lib

##########################
# Download Gson (2.10.1) #
##########################
GSON_URL="https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
GSON_DEST_SERVER="MusicServer/lib/gson.jar"
GSON_DEST_CLIENT="MusicClient/lib/gson.jar"

echo "Downloading Gson..."
if [ ! -f "$GSON_DEST_SERVER" ]; then
    curl -L -o "$GSON_DEST_SERVER" "$GSON_URL"
    echo "Gson downloaded to MusicServer/lib/gson.jar"
else
    echo "Gson already exists in MusicServer/lib."
fi

if [ ! -f "$GSON_DEST_CLIENT" ]; then
    cp "$GSON_DEST_SERVER" "$GSON_DEST_CLIENT"
    echo "Gson copied to MusicClient/lib/gson.jar"
else
    echo "Gson already exists in MusicClient/lib."
fi

###################################
# Download Jaudiotagger (2.0.1)    #
###################################
JAUDIOTAGGER_URL="https://repo1.maven.org/maven2/org/jaudiotagger/jaudiotagger/2.0.1/jaudiotagger-2.0.1.jar"
JAUDIOTAGGER_DEST="MusicServer/lib/jaudiotagger.jar"

echo "Downloading Jaudiotagger..."
if [ ! -f "$JAUDIOTAGGER_DEST" ]; then
    curl -L -o "$JAUDIOTAGGER_DEST" "$JAUDIOTAGGER_URL"
    echo "Jaudiotagger downloaded to MusicServer/lib/jaudiotagger.jar"
else
    echo "Jaudiotagger already exists in MusicServer/lib."
fi

# Validate the Jaudiotagger JAR by checking its ZIP signature “PK”
fileHeader=$(head -c 4 "$JAUDIOTAGGER_DEST")
if [[ "$fileHeader" != "PK"* ]]; then
    echo "Error: Downloaded jaudiotagger.jar does not appear to be a valid JAR."
    rm "$JAUDIOTAGGER_DEST"
    exit 1
fi

##################################
# Download JLayer (1.0.1) for MP3#
##################################
JLAYER_URL="https://repo1.maven.org/maven2/javazoom/jlayer/1.0.1/jlayer-1.0.1.jar"
JLAYER_DEST="MusicClient/lib/jlayer.jar"

echo "Downloading JLayer..."
if [ ! -f "$JLAYER_DEST" ]; then
    curl -L -o "$JLAYER_DEST" "$JLAYER_URL"
    echo "JLayer downloaded to MusicClient/lib/jlayer.jar"
else
    echo "JLayer already exists in MusicClient/lib."
fi

############################################################
# Download JavaFX Modules (Version 17.0.2) for Media Playback #
############################################################
# Detect OS for selecting the proper JavaFX classifier.
os=""
case "$(uname)" in
    Darwin)
       os="mac"
       ;;
    Linux)
       os="linux"
       ;;
    MINGW*|CYGWIN*|MSYS*)
       os="win"
       ;;
    *)
       echo "Unsupported OS for JavaFX downloads."
       exit 1
       ;;
esac

JAVA_FX_VERSION="17.0.2"

FX_BASE_URL="https://repo1.maven.org/maven2/org/openjfx/javafx-base/${JAVA_FX_VERSION}/javafx-base-${JAVA_FX_VERSION}-${os}.jar"
FX_GRAPHICS_URL="https://repo1.maven.org/maven2/org/openjfx/javafx-graphics/${JAVA_FX_VERSION}/javafx-graphics-${JAVA_FX_VERSION}-${os}.jar"
FX_MEDIA_URL="https://repo1.maven.org/maven2/org/openjfx/javafx-media/${JAVA_FX_VERSION}/javafx-media-${JAVA_FX_VERSION}-${os}.jar"

FX_BASE_DEST="MusicClient/lib/javafx-base.jar"
FX_GRAPHICS_DEST="MusicClient/lib/javafx-graphics.jar"
FX_MEDIA_DEST="MusicClient/lib/javafx-media.jar"

echo "Downloading JavaFX base..."
if [ ! -f "$FX_BASE_DEST" ]; then
    curl -L -o "$FX_BASE_DEST" "$FX_BASE_URL"
    echo "JavaFX base downloaded to $FX_BASE_DEST"
else
    echo "JavaFX base already exists in MusicClient/lib."
fi

echo "Downloading JavaFX graphics..."
if [ ! -f "$FX_GRAPHICS_DEST" ]; then
    curl -L -o "$FX_GRAPHICS_DEST" "$FX_GRAPHICS_URL"
    echo "JavaFX graphics downloaded to $FX_GRAPHICS_DEST"
else
    echo "JavaFX graphics already exists in MusicClient/lib."
fi

echo "Downloading JavaFX media..."
if [ ! -f "$FX_MEDIA_DEST" ]; then
    curl -L -o "$FX_MEDIA_DEST" "$FX_MEDIA_URL"
    echo "JavaFX media downloaded to $FX_MEDIA_DEST"
else
    echo "JavaFX media already exists in MusicClient/lib."
fi

echo "=== External Libraries Downloaded and Verified ==="
echo "=== Running Build Script ==="
./build.sh
