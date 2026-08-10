# 2巻「とあるところに」を app2 として追加

既存の SoundNovelApp リポジトリに、2巻をWebView版アプリとして追加します。
**1巻（app/）には一切変更を加えません。**

---

## v1.2 での修正（起動直後に落ちる問題）

「繰り返し停止しています」はネイティブ側のクラッシュだった。
原因を切り分けられるよう、次のように作り直した。

| 対策 | 内容 |
|---|---|
| 例外の可視化 | onCreate 全体を try/catch し、失敗時は原因を画面に表示する |
| テーマ変更 | `Theme.Black.NoTitleBar.Fullscreen` → `Theme.NoTitleBar` |
| 全画面化 | 失敗しても本体は動くよう分離（例外を握りつぶす） |
| 向き固定 | Manifest から外し、Kotlin 側で try 付きで指定 |
| WebView設定 | `allowFileAccessFromFileURLs` 等の非推奨APIを削除 |
| meta読み込み | 動的 script 生成 → `<script src>` の静的タグへ |

これで、万一まだ問題があっても**黒画面で落ちる代わりに
エラー内容が画面に出る**ようになった。

---

## 以前の対応（v1.1）

初版は 4.4MB の JavaScript を一括で読み込む作りだったため、
WebView がメモリ不足で落ちていました。次のように作り直しています。

| 対策 | 内容 |
|---|---|
| 画像を実ファイル化 | base64 埋め込みをやめ、`bg/` `ch/` `fx/` に WebP を配置 |
| データを章分割 | `data/ch1.js`〜`ch4.js`。開いた章だけを読み込む |
| 読み込み方式 | `file://` では XHR が CORS で塞がれるため、script タグ方式へ変更 |
| 起動時の負荷 | 初回は meta（6KB）＋第一章（86KB）のみ |

これにより起動時のメモリ消費が大幅に下がりました。

---

## 構成

```
SoundNovelApp/
  app/          1巻 サウンドノベル（ネイティブKotlin版）── 変更なし
  app2/         2巻 とあるところに（WebView版）── 今回追加
    src/main/assets/
      index.html        ゲームエンジン（28KB）
      data/meta.js      章・分岐・エンド定義（6KB）
      data/ch1〜4.js    各章のシーン（86/51/44/30KB）
      bg/               背景 20点（956KB）
      ch/               立ち絵 36点（1.8MB）
      fx/               演出 2点（260KB）
  debug.keystore
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
git commit -m "fix novel2: split assets to avoid webview crash"
git push
```

`setup_app2.sh` は何度実行しても壊れません。

---

## ビルド結果

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
| 総容量 | assets 3.5MB |

---

## 検証済み

- 起動 → タイトル → 本編再生（JSエラーなし）
- 全4章の遅延読み込み（193/107/90/69シーン）
- 分岐の選択と状態変化
- 参照アセットの欠損なし（背景20・立ち絵36を全数突合）

---

## 更新のしかた

物語を変えたときは `data/ch*.js` を、
画像を足したときは `bg/` `ch/` にファイルを置くだけです。
エンジン（`index.html`）を触るのは、分岐やUIを変えるときだけです。
