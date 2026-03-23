#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <input-image>"
    exit 1
fi

input="$1"
res="app/src/main/res"

# Legacy launcher icons
convert "$input" -resize 48x48   "$res/mipmap-mdpi/ic_launcher.png"
convert "$input" -resize 72x72   "$res/mipmap-hdpi/ic_launcher.png"
convert "$input" -resize 96x96   "$res/mipmap-xhdpi/ic_launcher.png"
convert "$input" -resize 144x144 "$res/mipmap-xxhdpi/ic_launcher.png"
convert "$input" -resize 192x192 "$res/mipmap-xxxhdpi/ic_launcher.png"

# Adaptive icon foreground layers
convert "$input" -resize 108x108 -gravity center -background none -extent 108x108 "$res/mipmap-mdpi/ic_launcher_foreground.png"
convert "$input" -resize 162x162 -gravity center -background none -extent 162x162 "$res/mipmap-hdpi/ic_launcher_foreground.png"
convert "$input" -resize 216x216 -gravity center -background none -extent 216x216 "$res/mipmap-xhdpi/ic_launcher_foreground.png"
convert "$input" -resize 324x324 -gravity center -background none -extent 324x324 "$res/mipmap-xxhdpi/ic_launcher_foreground.png"
convert "$input" -resize 432x432 -gravity center -background none -extent 432x432 "$res/mipmap-xxxhdpi/ic_launcher_foreground.png"

echo "Icons generated from $input"
