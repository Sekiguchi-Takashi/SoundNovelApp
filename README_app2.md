# 2巻「とあるところに」を app2 として追加

既存の SoundNovelApp リポジトリに、2巻をWebView版アプリとして追加します。
**1巻（app/）には一切変更を加えません。**

---

## 構成

```
SoundNovelApp/
  app/          1巻 サウンドノベル（ネイティブKotlin版）── 変更なし
  app2/         2巻 とあるところに（WebView版）── 今回追加
  debug.keystore
  settings.gradle.kts     :app2 を追記
  .github/workflows/      両方をビルドするよう更新
```

パッケージ名が違うため（`com.sekiguchi.soundnovel` と `com.sekiguchi.toaru`）、
端末には**2つのアプリとして並んでインストール**されます。

---

## 導入手順

```
cd ~
unzip -o /sdcard/Download/novel2_pack.zip -d SoundNovelApp
cd ~/SoundNovelApp
bash setup_app2.sh
git add -A
git commit -m "add novel2 (toaru) as app2"
git push
```

`setup_app2.sh` がやること:

1. `settings.gradle.kts` に `include(":app2")` を追記
2. Actions のワークフローを、1巻と2巻の両方をビルドする形へ更新
3. `gradle.properties` に必要な設定がなければ追記

何度実行しても壊れません（追記済みなら skip します）。

---

## ビルド結果

Actions の成果物が2つ出ます。

| 成果物 | 内容 |
|---|---|
| `novel1-soundnovel-apk` | 1巻（app-debug.apk） |
| `novel2-toaru-apk` | 2巻（app2-debug.apk） |

---

## 2巻の中身

| 項目 | 内容 |
|---|---|
| 収録 | 全四章 459シーン（原作のまま） |
| 分岐 | 20箇所 |
| 手掛かり | 20件 |
| エンディング | 7種 |
| 画像 | 立ち絵36点 / 背景20点 / 演出2点（WebP埋め込み） |
| 容量 | game_data.js が 4.2MB |

`app2/src/main/assets/` の2ファイルがゲーム本体です。

- `index.html` … ゲームエンジン（分岐・UI・セーブ）
- `game_data.js` … 全シーンと画像

物語やアセットを更新するときは `game_data.js` を差し替えるだけで済みます。

---

## 将来の統合について

1巻もWebView版エンジンへ移植すれば、一本のアプリにまとめられます。
分岐・手掛かり・エンドコレクションはすでに2巻側の方が高機能なので、
統合するなら2巻のエンジンに1巻のシナリオを載せる形が自然です。
