#!/bin/sh
# Builds the Kotlin "Shared" framework for Xcode.
# Run by the iosApp target's "Compile Kotlin Framework" build phase.
set -e

if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
  echo "Skipping Gradle build (OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES)"
  exit 0
fi

# This build (Gradle 8.14, Android Gradle Plugin 8.13) runs on JDK 17 to 24.
# On JDK 25 or newer, Gradle stops with nothing but the version number
# ("What went wrong: 25.0.4"). Xcode does not see the JAVA_HOME set in your
# shell profile, so look for a supported JDK rather than taking the first one.
MIN_JDK=17
MAX_JDK=24

# Prints the major version of the JDK at $1 (8, 17, 21, 25...), or nothing.
# Every command may fail without stopping the script: some shells apply set -e
# inside command substitutions.
jdk_major() {
  ver=""
  if [ -f "$1/release" ]; then
    ver=$(sed -n 's/^JAVA_VERSION="\([^"]*\)".*/\1/p' "$1/release" 2>/dev/null || true)
  fi
  if [ -z "$ver" ] && [ -x "$1/bin/java" ]; then
    ver=$("$1/bin/java" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p' || true)
  fi
  case "$ver" in 1.*) ver=${ver#1.} ;; esac
  echo "${ver%%[!0-9]*}"
}

supported_jdk() {
  [ -n "$1" ] && [ -x "$1/bin/java" ] || return 1
  major=$(jdk_major "$1")
  [ -n "$major" ] && [ "$major" -ge "$MIN_JDK" ] && [ "$major" -le "$MAX_JDK" ]
}

pick_jdk() {
  # 1. JAVA_HOME, when Xcode has one (for example a User-Defined build setting).
  if supported_jdk "$JAVA_HOME"; then echo "$JAVA_HOME"; return 0; fi
  # 2. JDKs registered with macOS (/Library/Java/JavaVirtualMachines and
  #    ~/Library/Java/JavaVirtualMachines), preferring 21.
  if [ -x /usr/libexec/java_home ]; then
    for v in 21 17 24 23 22 20 19 18; do
      home=$(/usr/libexec/java_home -F -v "$v" 2>/dev/null) || continue
      if supported_jdk "$home"; then echo "$home"; return 0; fi
    done
  fi
  # 3. Homebrew JDKs and the JDK bundled with Android Studio.
  for home in \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if supported_jdk "$home"; then echo "$home"; return 0; fi
  done
  return 1
}

if JDK=$(pick_jdk); then
  export JAVA_HOME="$JDK"
  echo "Using JDK $(jdk_major "$JAVA_HOME") at $JAVA_HOME"
else
  found=""
  if [ -n "$JAVA_HOME" ]; then
    major=$(jdk_major "$JAVA_HOME")
    if [ -n "$major" ]; then
      found=" JAVA_HOME points to JDK $major, which is outside that range."
    else
      found=" JAVA_HOME ($JAVA_HOME) does not contain a JDK."
    fi
  fi
  echo "error: No JDK $MIN_JDK to $MAX_JDK found for the Gradle build.$found Install JDK 21 (for example: brew install --cask temurin@21), or add a User-Defined build setting JAVA_HOME that points to a JDK $MIN_JDK to $MAX_JDK."
  exit 1
fi

cd "$SRCROOT/../../.."
./gradlew :samples:ojol-mitra:shared:embedAndSignAppleFrameworkForXcode
