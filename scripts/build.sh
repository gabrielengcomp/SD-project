#!/usr/bin/env bash
# Compila todo o projeto para out/
set -e
cd "$(dirname "$0")/.."
rm -rf out && mkdir -p out
javac -encoding UTF-8 -d out $(find src -name "*.java")
echo "Compilado em out/"
