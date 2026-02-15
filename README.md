# QR Tidy 📦

**QRコードで「仕舞いたいもの」を管理するアプリ**

袋（仕舞うもの）と箱（仕舞う場所）にQRコードを貼り、スキャンするだけで「何をどこに仕舞ったか」をNotionデータベースに自動記録します。プラモデルの余剰パーツ、工具、文具、季節の衣類——何でも整理できます。

<img src="画面イメージ.png" width="300" alt="画面イメージ">  <img src="Notion画面.png" width="300" alt="Notion画面">

## 使い方

1. **QRシールを印刷する** — [QRPrint](https://github.com/unafi/PlamoScanner/tree/main/QRprint) でA4ラベルシート用のQRコードPDFを生成し、ラベルシールに印刷。
2. **シールを貼る** — 仕舞いたいもの（袋）と、仕舞う場所（箱）にそれぞれ貼る。
3. **「箱にしまう」をタップ** — カメラが起動するので、**箱 → 袋** の順にQRコードをスキャンする。
4. **Notionに自動記録** — Notionにレコードが作成/更新され、箱のページが自動で開く。
5. **Notionで詳細を編集** — 箱の名前、中身の写真、メモなどを自由に追記。

### その他の機能

| 機能 | 説明 |
|:---|:---|
| **袋スキャン** | 袋のQRをスキャンして情報確認。未登録なら自動作成。 |
| **箱スキャン** | 箱のQRをスキャンして情報確認。未登録なら自動作成。 |
| **NFC対応** | Android版ではNFCタグ（RFID）にも対応。QRとNFCを同時に待ち受け。 |
| **写真撮影** | スキャン時に写真を撮影し、Notionページに自動アップロード。 |

## Notionデータベース構成

アプリが連携するNotionデータベースには、以下の最低限の項目が必要です。写真やメモなどの列は自由に追加してください。

### 袋マスター
| プロパティ | 型 | 説明 |
|:---|:---|:---|
| **袋ID** | Title | QRコードの文字列（PK） |
| **商品名** | Rich Text | 中身の名称 |
| **現在の箱** | Relation | 箱マスターへのリンク |
| **写真** | Files | スキャン時に撮影した画像（自動アップロード） |

### 箱マスター
| プロパティ | 型 | 説明 |
|:---|:---|:---|
| **箱ID** | Title | QRコードの文字列（PK） |
| **箱名** | Rich Text | 収納場所の名称 |
| **写真** | Files | スキャン時に撮影した画像（自動アップロード） |

> **Tip:** 袋マスターの「現在の箱」リレーションを**双方向**にしておくと、箱のページに中身の一覧が表示されて便利です。

## プロジェクト構成

**Kotlin Multiplatform (KMP)** + **Compose Multiplatform** で構築されたクロスプラットフォームアプリです。

```
QRTidy/
├── composeApp/
│   └── src/
│       ├── androidMain/    ← Android版ソース（CameraX, Retrofit, NFC）
│       ├── iosMain/        ← iOS版ソース（準備中）
│       ├── commonMain/     ← 共通コード
│       └── jvmMain/        ← Desktop版（準備中）
└── iosApp/                 ← Xcode プロジェクト
```

## セットアップ

### 1. Notion の準備

1. [Notion Integrations](https://www.notion.so/my-integrations) でインテグレーションを作成し、**Internal Integration Secret** を取得。
2. 上記の構成で袋マスター・箱マスターのデータベースを作成。
3. 各データベースのページで「コネクト」からインテグレーションを追加してアクセス権を付与。

### 2. シークレットファイルの作成

`SecretConfig.kt.sample` をコピーして `SecretConfig.kt` を作成し、実際の値を設定します。

```
composeApp/src/androidMain/kotlin/dev/unafi/qrtidy/SecretConfig.kt
```

```kotlin
package dev.unafi.qrtidy

object SecretConfig {
    const val NOTION_API_KEY = "ntn_あなたのキー"
    const val DATABASE_ID_HUKURO = "袋マスターのDB ID (32桁)"
    const val DATABASE_ID_HAKO = "箱マスターのDB ID (32桁)"
}
```

> **Note:** `SecretConfig.kt` は `.gitignore` に含まれており、リポジトリにはpushされません。

### 3. Android版のビルドと実行

**必要なもの:** Android Studio, Android実機（カメラ・NFC搭載）

1. Android Studio で `QRTidy` プロジェクトを開く。
2. Gradle Sync を実行。
3. 実機を接続し、Run ▶ で実行。

**コマンドラインでビルド:**
```shell
# Windows
.\gradlew.bat :composeApp:assembleDebug

# macOS/Linux
./gradlew :composeApp:assembleDebug
```

## 技術スタック

| カテゴリ | 技術 |
|:---|:---|
| **言語** | Kotlin (KMP) |
| **UI** | Jetpack Compose / Compose Multiplatform (Material3) |
| **カメラ / QR** | CameraX + Google ML Kit Barcode Scanning |
| **NFC** | Android NfcAdapter (Reader Mode) |
| **通信** | Retrofit2 + OkHttp + Gson |
| **バックエンド** | Notion API (直接通信) |

## 関連プロジェクト

- [PlamoScanner](https://github.com/unafi/PlamoScanner) — 本プロジェクトの前身（Android単体版）
- [PlamoScannerPWA](https://github.com/unafi/PlamoScannerPWA) — PWA版（ブラウザで動作、GAS経由）

## ライセンス

MIT License