#!/bin/sh
cd ~/robocode
. ./set_java_options.sh
java \
  -cp "libs/*" \
  -Xmx512M \
  -Xdock:name=Robocode \
  -Xdock:icon=robocode.ico \
  -XX:+IgnoreUnrecognizedVMOptions \
  "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED" \
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED" \
  "--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED" \
  "--add-opens=java.desktop/sun.awt=ALL-UNNAMED" \
  robocode.Robocode "$@"
