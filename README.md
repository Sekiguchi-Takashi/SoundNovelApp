# 終電の向こう側（サウンドノベル）

Termux + GitHub Actions ビルド前提のサウンドノベルエンジン。
外部依存ゼロ / XMLレイアウトなし / プログラマティックUI。

## 収録内容 (v1.0)
- 第一話「終電の向こう側」
- 第二話「灯りの街」→ ラストで2択分岐
  - A「旅館へ向かう」= 原作ルート（ep03 未収録のため「続く」表示）
  - B「駅へ引き返す」= ifルート「帰り道」（完結）
- 背景10枚（Python生成）/ 環境音3種（走行音・ドローン・祭囃子）
- オートセーブ（つづきから再開時に背景・音も復元）

## ビルド
GitHubに新規リポジトリを作り、このフォルダの中身をpushするだけ。
Actionsが自動で app-debug.apk を成果物に出力します。
（AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.9 / debug.keystore 同梱）

    cd SoundNovelApp
    git init
    git add -A
    git commit -m "v1.0 ep01-ep02 + if route"
    git branch -M main
    git remote add origin https://github.com/Sekiguchi-Takashi/SoundNovelApp.git
    git push -u origin main

## 話を追加する手順（第三話以降）
1. チャットに本文を貼り「第三話」と指示
2. 生成された ep03.json を app/src/main/assets/scenario/ へ、
   新規背景 *.jpg を assets/bg/ へ置く
3. manifest.json の "episodes" に "ep03" を追記
4. push すれば再ビルド。選択肢Aの「続く」画面が自動的に第三話へ繋がる

## シナリオ形式（scenario/*.json）
    {"t":"title","v":"第三話　○○"}   話タイトルカード
    {"t":"bg","v":"ryokan"}          背景切替（bg/名前.jpg）
    {"t":"se","v":"matsuri"}         環境音（train/ambient/matsuri/stop）
    {"t":"l","v":"本文1行"}          タップで進む1行
    {"t":"p"}                        ページクリア
    {"t":"choice","q":"？","a":{"v":"…","goto":"epXX"},"b":{…}}
    {"t":"goto","v":"ep04"}          次の話へ（無ければ「続く」）
    {"t":"end","v":"fin"}            完結（タイトルへ）
