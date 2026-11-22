#!/usr/bin/env bash
# Generates a small demo music library + matching playlist JSON for Slotify.
# Run: ./scripts/make-demo-fixtures.sh  (requires ffmpeg)
set -euo pipefail

DIR="${HOME}/slotify-demo-music"
mkdir -p "$DIR"

tone() { # $1=out file  $2=seconds  $3=freq
  ffmpeg -y -loglevel error -f lavfi -i "sine=frequency=$3:duration=$2" "$1"
}
tagged() { # $1=out  $2=title  $3=artist  $4=album  $5=seconds  $6=freq
  ffmpeg -y -loglevel error -f lavfi -i "sine=frequency=$6:duration=$5" \
    -metadata "title=$2" -metadata "artist=$3" -metadata "album=$4" \
    "$DIR/$1"
}

# Tagged files (scan reads tags)
tagged "blue-moon.mp3"       "Blue Moon"            "Billie Holiday"   "An Evening with Billie" 3 330
tagged "summertime.mp3"      "Summertime"           "Ella Fitzgerald"  "Porgy and Bess"          3 349
tagged "take-five.mp3"       "Take Five"            "Dave Brubeck"     "Time Out"                4 392
tagged "georgia.mp3"         "Georgia On My Mind"   "Ray Charles"      "The Genius of Ray"       4 415
tagged "route-66.mp3"        "Route 66"             "Nat King Cole"    "King Cole Trio"          3 440
tagged "mood-indigo.mp3"     "Mood Indigo"          "Duke Ellington"   "Ellington at Newport"    4 466
tagged "body-and-soul.mp3"   "Body and Soul"        "Coleman Hawkins"  "Body and Soul"           5 494

# Untagged file: filename must carry artist - title
tone "$DIR/Ella Fitzgerald - Cheek to Cheek.mp3" 3 523

# Playlist JSON whose tracks deliberately vary (remaster suffix, near-misses) + 2 unmatched
cat > "$DIR/classic-jazz-demo.json" << 'EOF'
{
  "name": "Classic Jazz (demo)",
  "description": "Demo playlist for Slotify — matches the generated library",
  "tracks": [
    { "name": "Blue Moon - 2011 Remaster", "artists": ["Billie Holiday"], "album": "An Evening with Billie", "durationMs": 3100 },
    { "name": "Summertime", "artists": ["Ella Fitzgerald", "Louis Armstrong"], "album": "Porgy and Bess", "durationMs": 3000 },
    { "name": "Take Five", "artists": ["Dave Brubeck"], "album": "Time Out", "durationMs": 4000 },
    { "name": "Georgia On My Mind", "artists": ["Ray Charles"], "album": "The Genius of Ray", "durationMs": 4100 },
    { "name": "Route 66", "artists": ["Nat King Cole"], "album": "King Cole Trio", "durationMs": 3050 },
    { "name": "Mood Indigo", "artists": ["Duke Ellington"], "album": "Ellington at Newport", "durationMs": 4000 },
    { "name": "Body and Soul", "artists": ["Coleman Hawkins"], "album": "Body and Soul", "durationMs": 4950 },
    { "name": "Cheek to Cheek", "artists": ["Ella Fitzgerald"], "durationMs": 3000 },
    { "name": "Nonexistent Tune", "artists": ["Nobody Famous"], "durationMs": 180000 },
    { "name": "Another Missing Song", "artists": ["The Unknowns"], "durationMs": 200000 }
  ]
}
EOF

echo "Demo library ready: $DIR"
echo "Run Slotify with: SLOTIFY_DEMO=1 ./gradlew :composeApp:run"
