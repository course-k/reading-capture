# reading-capture

本を読みながら、ページの写真と話した言葉を手間なく取り込むための Android アプリ（個人用）。

最終更新: 2026-11-20

## 状態

アプリ全体を作り、エミュレーターとテストで確かめた段階。実機（Pixel 6a）ではまだ動かしていない。

| できること | 作った | エミュレーター・テストで確かめた | 実機で確かめた |
| --- | --- | --- | --- |
| 撮る（自動・シャッター・音量ボタン・直前の 1 枚を消す・画面を消さない） | ○ | ○ | 未 |
| 知らせる（欄外の切れ・ぶれ・暗い） | ○ | ○ | 未 |
| 上げる（端末にためて、つながったら取り込み箱へ） | ○ | ○（本物の GitHub に対しても） | 未 |
| 本と章・節を選ぶ | ○ | ○ | 未 |
| 話す（章・節と日時を付けて残す。下書きは閉じても残る） | ○ | ○ | 未 |
| 共有から受け取る | ○ | ○ | 未 |

実機でしか確かめられないこと: 振動の違いが台の上で分かるか、音量ボタンを押したときに音量が変わらないか、検知のしきい値（撮り逃し・二度撮り）、写真 1 枚の大きさ、ピントが合うまでの時間。

## できること

- **撮る**: カメラを本のページに向けて止めると、シャッターを押さなくても自動で撮る。1 回に 1 ページ。撮れたら短く振動する。めくって次のページを向けると、また撮る。同じページは二度撮らない。シャッターと音量ボタンでも撮れる。写真は切り抜かずに全体を保存する（長い辺は 3072 画素まで）
- **知らせる**: 文字が画像の端にかかっている・ぶれている・暗すぎるときは、長めの 2 回の振動と画面の表示で知らせる。「撮り直す」を押すと、その 1 枚を消して、本を直して止まったところでもう一度撮る
- **上げる**: 写真と話した言葉を、本人だけが見られる取り込み箱（非公開の GitHub リポジトリ）に上げる。電波が無いときは端末にためて、つながったときに上げる。取り込み箱に上がったと確かめられるまで、端末から消さない
- **本と章・節**: 取り込み箱に置かれた本の一覧と目次を読み、本を切り替えられる。アプリ自身はインターネットから目次を探さない
- **話す**: 入力欄に音声入力で話した言葉を、選んだ本・章・節と日時を付けて、そのままの文字で残す。音声認識はアプリに持たせず、端末の音声入力（キーボードの横のボタン）を使う
- **共有から受け取る**: ほかのアプリの「共有」から画像を受け取り、選んでいる本の写真として上げる

## 作り

Kotlin と Jetpack Compose。モジュールは 2 つ。

| モジュール | 中身 |
| --- | --- |
| `core` | カメラにも Android にも依存しない部品。「動いた → 止まった」の判定、同じページかどうかの判定、切れ・ぶれ・暗さの判定、取り込み箱の並び、GitHub へ上げる処理、目次。Mac の上でテストできる |
| `app` | Android アプリ。カメラ（CameraX）、写真の仕上げ、端末の中の台帳、上げる処理の見張り、画面 |

### 撮る判定の考え方

スマホは台に固定するので、映像が変わるのはページをめくるときと手が入るとき。

1. 映像のコマどうしの差を見て、止まったかを判断する（全体の明るさが一様に変わった分は打ち消す）
2. 止まったら、最後に撮った 1 枚と比べる。違うページなら撮る。同じページなら、次に動くまで何もしない
3. 同じページかどうかは、行や段の縞を取り除いたあとの「文字の粒の模様」を、区画ごとに少しずつずらして比べる。同じ本のページは行の位置がそろっていて、縮めた画像どうしをそのまま比べると違うページでも似て見えるため

撮った 1 枚は、向きを直して保存したあと点検する。切れは、端末の中の文字認識（ML Kit）で「文字の行がどこにあるか」だけを取り出して判断する。読み取った文字は受け取らず、どこにも残さない。1 ページずつ撮ると、となりのページの文字が画像の端に写り込むので、本文のかたまりから大きく離れたかたまりは数えない。

### 上げる処理の考え方

GitHub の Git Database API（blob → tree → commit → ref）を使い、写真と言葉をまとめて 1 コミットで置く。

- 中身（blob）の名前は中身から決まるので、上げ直しても増えない
- 置く前に取り込み箱の今の中身を見て、もう同じものがあれば何もしない（「置けたのに返事が届かなかった」あとにやり直しても、二重に置かない）
- ブランチは上書きしない。ほかから先に push されていたら、その上に載せ直す
- 鍵（fine-grained personal access token）は、端末の鍵の金庫（Android Keystore）で暗号化して置く。必要な権限は、取り込み箱 1 つに対する Contents の Read and write だけ

取り込み箱の中の並びは `core/src/main/kotlin/app/readingcapture/core/inbox/InboxLayout.kt` にある。

### 見た目

白・黒・グレーを基調に、差し色は青 1 色。知らせ（切れ・ぶれ・暗い）だけが別の色。明るい表示と暗い表示の両方に対応する。見本は `docs/design/mockup.html`。

## 動かし方

要るもの: JDK 17、Android SDK（platform-tools、platforms;android-37.1、build-tools;36.0.0）。Android Studio は要らない。

`local.properties`（リポジトリには入れない）に、SDK の場所と、取り込み箱の名前の既定値を書く。

```properties
sdk.dir=/path/to/android-sdk
inbox.repo=<持ち主>/<取り込み箱のリポジトリ>
```

```sh
export JAVA_HOME=/path/to/jdk17

./gradlew :app:assembleDebug        # ビルド。app/build/outputs/apk/debug/app-debug.apk ができる
adb install -r app/build/outputs/apk/debug/app-debug.apk   # USB でつないだ端末に入れる
```

`scripts/install.sh` は、ビルドして、USB でつないだ端末に入れるところまでを 1 回で行う。

USB（adb）で入れるアプリは、Android の開発者確認の対象外（<https://developer.android.com/developer-verification/guides/faq>、2026-11-20 に確認）。

## テスト

```sh
./gradlew :core:test                      # Mac の上で動く。合成した映像と画像で判定を確かめる
./gradlew :app:connectedDebugAndroidTest  # エミュレーターか実機の上で動く。台帳の決まりと、文字認識を使った切れの判定
```

- `core` のテストは、乱数で選んだ文字を並べた合成のページを使う。本物の本のページは使わない。明るさの変化、手や指の写り込み、影、ページの反り、めくりかけて戻す動き、ピントの揺れ、撮れたときの振動の揺れを、台本にした映像で流す
- `./gradlew :core:test --tests '*SampleGenerator*' -Psamples=1` で、エミュレーターのカメラに見せる合成の画像と映像のコマを `core/build/samples/` に書き出す。エミュレーターは `-camera-back videofile:<動画>` で動画をカメラの映像にできる
- 環境変数 `INBOX_LIVE_REPO` と `INBOX_LIVE_TOKEN` を渡して `--tests '*LiveGitHubTest*'` を動かすと、本物の GitHub に対して上げる処理を確かめる（乱数の中身を `selftest/` の下に置いて消す）

## 合わせ込み

検知の数値を、端末の中の `files/diag/<年-月-日>.jsonl` に 1 行 1 件で記録している（数値だけで、写真の中身や文字は入れない）。

```sh
adb exec-out run-as app.readingcapture cat files/diag/2026-11-20.jsonl
```

`scripts/pull-diag.sh [年-月-日]` は、記録を取り出して、撮った回数・1 枚ごとの鮮明さと知らせ・「同じページ」と判断した回数・写真の大きさ・上げる処理のまとめを出す。

撮り逃しや二度撮りが起きたときの数値を見て、`core` の `EngineConfig` / `MatcherConfig` / `QualityConfig` のしきい値を直す。設定の画面で、数値を「撮る」の画面に出すこともできる。

## 使っているものの版

2026-11-20 に、各公式ページと Maven の一覧で確かめた。`gradle/libs.versions.toml` にある。

| もの | 版 | 確かめたページ |
| --- | --- | --- |
| Android Gradle Plugin | 9.5.1 | <https://developer.android.com/build/releases/gradle-plugin> |
| Gradle | 9.8.2 | <https://docs.gradle.org/9.8.2/userguide/compatibility.html> |
| Kotlin | 2.4.21 | <https://kotlinlang.org/docs/releases.html> |
| Compose BOM | 2026.11.01 | <https://developer.android.com/develop/ui/compose/bom/bom-mapping> |
| CameraX | 1.7.0 | <https://developer.android.com/jetpack/androidx/releases/camera> |
| WorkManager | 2.12.0 | <https://developer.android.com/jetpack/androidx/releases/work> |
| ML Kit 文字認識（日本語、同梱） | 16.0.1 | <https://developers.google.com/ml-kit/vision/text-recognition/v2/android> |
| OkHttp | 5.5.1 | <https://github.com/square/okhttp/blob/master/CHANGELOG.md> |
| kotlinx.serialization | 1.13.0 | <https://github.com/Kotlin/kotlinx.serialization/releases> |

- コンパイル先は API 37.1（Compose 1.13 が求める）、動かす先は API 33 以上
- Gradle 9.8.2 と Kotlin 2.4.21 と AGP 9.5.1 の組み合わせは、公式の「検証済み」の範囲を少し超えるが、この組み合わせでビルドとテストが通ることを実際に確かめた

## このリポジトリに入れないもの

- 本のページの写真、本文
- アクセストークン、署名鍵、端末固有の設定（`.gitignore` で除外）
- 取り込み箱の名前（`local.properties` に書く）
