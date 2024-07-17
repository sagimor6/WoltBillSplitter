#!/bin/bash
set -e
function runGradle() {
  echo running ./gradlew "$@"
  if ./gradlew "$@"; then
    echo succeeded: ./gradlew "$@"
  else
    echo failed: ./gradlew "$@"
    return 1
  fi
}
function regenerateVerificationMetadata() {
  echo "regenerating verification metadata"
  runGradle --refresh-dependencies --write-verification-metadata sha256 clean assemble || true
  sed -ri 's/^(\s*<sha256\s.*)\sorigin="Generated by Gradle"\s*\/>\s*$/\1\/>/' gradle/verification-metadata.xml
  sed -i 's/\r$//' gradle/verification-metadata.xml
}
regenerateVerificationMetadata
echo
