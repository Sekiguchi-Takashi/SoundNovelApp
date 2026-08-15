#!/bin/bash
# SoundNovelApp リポジトリに 2巻（WebView版）を app2 として追加する。
# 既存の app/（1巻ネイティブ版）には一切触れない。
#
# 使い方:
#   cd ~/SoundNovelApp
#   bash setup_app2.sh

set -e

if [ ! -f settings.gradle.kts ]; then
  echo "エラー: SoundNovelApp のルートで実行してください"
  exit 1
fi

echo "== settings.gradle.kts に :app2 を追加 =="
if grep -q '":app2"' settings.gradle.kts; then
  echo "  すでに追加済みです"
else
  # include(":app") の行の後ろに追記する
  if grep -q 'include(":app")' settings.gradle.kts; then
    sed -i 's/include(":app")/include(":app")\ninclude(":app2")/' settings.gradle.kts
  else
    echo 'include(":app2")' >> settings.gradle.kts
  fi
  echo "  追加しました"
fi

echo "== GitHub Actions のワークフローを更新 =="
# 注意: release.yml と ci/ はカタログ管理システムが使うため触れない。
#       ここで作るのは build.yml（開発中のAPK確認用）だけ。
mkdir -p .github/workflows
cat > .github/workflows/build.yml << 'YAML'
name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.9'

      # APK は Release から配布するため、Artifacts へは上げない
      # （無料枠 0.5GB が枯渇して "Artifact storage quota has been hit" になる）
      - name: Build 1巻 (SoundNovelApp)
        run: gradle :app:assembleDebug --no-daemon

      - name: Build 2巻 (とあるところに)
        run: gradle :app2:assembleDebug --no-daemon
YAML
echo "  更新しました"

echo "== gradle.properties を確認 =="
touch gradle.properties
grep -q 'org.gradle.jvmargs' gradle.properties \
  || echo 'org.gradle.jvmargs=-Xmx2560m' >> gradle.properties
grep -q 'android.useAndroidX' gradle.properties \
  || echo 'android.useAndroidX=true' >> gradle.properties
echo "  確認しました"

echo
echo "== 完了 =="
echo "  app/   … 1巻（ネイティブ版・変更なし）"
echo "  app2/  … 2巻 とあるところに（WebView版）"
echo
echo "次のコマンドで push してください:"
echo "  git add -A"
echo "  git commit -m \"add novel2 (toaru) as app2\""
echo "  git push"
