# Yaac - Yet Another Anypoint CLI

![GitHub License](https://img.shields.io/github/license/myst3m/yaac)
![GitHub Release](https://img.shields.io/github/release/myst3m/yaac)

**[English](README.md)**

MuleSoft Anypoint Platform向けの高速なCLIツール。ClojureとGraalVMで作られています。

## なぜYaac？

MuleSoftの公式CLI（anypoint-cli-v4）は出力が装飾されていて、sed/awkなどと組み合わせにくい。Yaacはパイプで繋げやすいシンプルな出力を目指しました。

## 特徴

- **公式CLIの約5倍速い** - ネイティブバイナリ + キャッシュ + 並列API呼び出し
- **HTTP/2対応** - Anypoint Platformへの通信が速い
- **kubectlっぽい出力** - パイプしやすい
- **JSON/YAML/EDN対応** - `-o json`で構造化データ出力
- **短縮UUID** - テーブルでは先頭8文字を表示、引数でも前方マッチで使える
- **組み込みMaven** - `yaac build`でMavenなしでビルド
- **マニフェストで一括デプロイ** - YAMLで複数アプリをまとめて操作

## はじめかた

```bash
# 1. 認証情報の登録
yaac config credential

# 2. ログイン
yaac login cc-app

# 3. デフォルトの組織・環境を設定
yaac config context T1 Production

# 4. 使う！
yaac get app
yaac deploy app my-app target=hy:leibniz
```

## コマンド一覧

| コマンド | エイリアス | 説明 | 詳細 |
|---------|-----------|------|------|
| `login` | - | Connected Appでログイン | [doc/login.md](doc/login.md) |
| `get` | `ls`, `list` | リソース一覧（org, env, app, api, asset...） | [doc/get.md](doc/get.md) |
| `describe` | `desc` | リソースの詳細情報 | [doc/describe.md](doc/describe.md) |
| `create` | `new` | リソース作成（org, env, api, connected-app...） | [doc/create.md](doc/create.md) |
| `update` | `upd` | リソース更新（状態変更, レプリカ, スコープ...） | [doc/update.md](doc/update.md) |
| `delete` | `rm`, `del` | リソース削除（`--dry-run` / `--force`対応） | [doc/delete.md](doc/delete.md) |
| `deploy` | `dep` | RTF / CH2.0 / Hybridへデプロイ | [doc/deploy.md](doc/deploy.md) |
| `upload` | `up` | JAR / RAML / OASをExchangeへアップロード | [doc/upload.md](doc/upload.md) |
| `download` | `dl` | APIプロキシをJARでダウンロード | [doc/download.md](doc/download.md) |
| `config` | `cfg` | デフォルトorg/env/target設定、認証情報管理 | [doc/config.md](doc/config.md) |
| `build` | - | 組み込みMavenでビルド | [doc/build.md](doc/build.md) |
| `logs` | - | CH2.0アプリログ表示 | [doc/logs.md](doc/logs.md) |
| `http` | - | デプロイ済みアプリにHTTPリクエスト | [doc/http.md](doc/http.md) |
| `auth` | - | OAuth2フロー（認可コード、クライアント認証、Azure） | [doc/auth.md](doc/auth.md) |
| `a2a` | - | A2A（Agent-to-Agent）プロトコルクライアント | [doc/a2a.md](doc/a2a.md) |
| `mcp` | - | MCP（Model Context Protocol）クライアント | [doc/mcp.md](doc/mcp.md) |
| `clear` | - | org内の全リソースをクリア（RTF/PS除く） | [doc/delete.md](doc/delete.md#clear) |

## よく使うパターン

```bash
# 一覧
yaac get org                        # 組織一覧（IDは短縮表示）
yaac get app                        # アプリ一覧（デフォルトorg/env）
yaac get app T1 Production          # org/env指定

# 短縮ID
yaac desc org b68cabda              # 先頭8文字で前方マッチ

# 出力フォーマット
yaac get app -o json                # JSON
yaac get app -o wide                # 拡張カラム
yaac get app -H | awk '{print $3}'  # ヘッダなし → パイプ

# デプロイ（省略形）
yaac deploy app my-app target=hy:leibniz

# アプリプロパティ付き
yaac deploy app my-app target=rtf:k1 +http.port=8081 +db.host=oracle.local

# マニフェストで一括デプロイ
yaac deploy manifest deploy.yaml
yaac deploy manifest deploy.yaml --dry-run
```

## グローバルオプション

```
-o, --output-format FORMAT    出力形式: short, wide, json, edn, yaml
-H, --no-header               ヘッダなし（パイプ用）
-F, --fields FIELDS           フィールド選択（-F id,name / -F +org-type）
-V, --http-trace              HTTP通信を表示
-X, --http-trace-detail       HTTP詳細（並列実行無効）
-Z, --no-cache                キャッシュ無効
-1, --http1                   HTTP/1.1を強制
-d, --debug                   デバッグログ
-h, --help                    ヘルプ
```

## インストール

### 必要なもの

- Java 17以上
- Clojure CLI: https://clojure.org/guides/install_clojure

### ネイティブバイナリ（おすすめ）

起動が15msくらいになります（JVMだと2秒くらい）。

```bash
# GraalVM 25+が必要
git clone https://github.com/myst3m/yaac && cd yaac
clj -T:build native-image
cp target/yaac-0.9.1 ~/.local/bin/yaac
```

### Uberjar

```bash
clj -T:build uber
java --enable-native-access=ALL-UNNAMED -jar target/yaac-0.9.1.jar
```

### Bash補完

```bash
mkdir -p ~/.local/share/bash-completion/completions
cp completions/yaac.bash ~/.local/share/bash-completion/completions/yaac
```

## 設定ファイル

| ファイル | 内容 |
|---------|------|
| `~/.yaac/credentials` | 認証情報（JSON） |
| `~/.yaac/session` | アクセストークン |
| `~/.yaac/config` | デフォルト設定（EDN） |
| `~/.yaac/cache` | 組織/環境キャッシュ |

## ライセンス

See [LICENSE](LICENSE) file.
