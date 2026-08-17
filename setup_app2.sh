#!/bin/bash
# SoundNovelApp リポジトリに 2巻（WebView版）を app2 として追加する。
# 既存の app/（1巻ネイティブ版）には一切触れない。
#
# 使い方:
#   cd ~/SoundNovelApp
#   bash setup_app2.sh

set -e

if [ ! -f settings.gradle.kts ]; then
  printf 'エラー: SoundNovelApp のルートで実行してください\n'
  exit 1
fi

# settings.gradle.kts に :app2 を追加
if grep -q '":app2"' settings.gradle.kts; then
  printf 'settings.gradle.kts : すでに追加済み\n'
else
  if grep -q 'include(":app")' settings.gradle.kts; then
    sed -i 's/include(":app")/include(":app")\ninclude(":app2")/' settings.gradle.kts
  else
    printf 'include(":app2")\n' >> settings.gradle.kts
  fi
  printf 'settings.gradle.kts : :app2 を追加\n'
fi

# build.yml は使わない（CIは release.yml のみ）
rm -f .github/workflows/build.yml

# gradle.properties
touch gradle.properties
grep -q 'org.gradle.jvmargs' gradle.properties \
  || printf 'org.gradle.jvmargs=-Xmx2560m\n' >> gradle.properties
grep -q 'android.useAndroidX' gradle.properties \
  || printf 'android.useAndroidX=true\n' >> gradle.properties

printf '完了: app/ は変更なし / app2/ を追加\n'
printf '次は deploy.sh を実行してください\n'
