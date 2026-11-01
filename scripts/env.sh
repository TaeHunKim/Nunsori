# source me
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export APP_ID="com.lsa.app"
export AVD_NAME="lsa_api36"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
