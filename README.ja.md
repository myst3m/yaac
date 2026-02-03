# Yaac - Yet Another Anypoint CLI

![GitHub License](https://img.shields.io/github/license/myst3m/yaac)
![GitHub Release](https://img.shields.io/github/release/myst3m/yaac)
![GitHub Workflow Status](https://img.shields.io/github/workflow/status/myst3m/yaac/CI)

**[English](README.md)**

MuleSoft Anypoint Platform向けの高速なCLIツール。ClojureとGraalVMで作られています。

## 目次

- [なぜYaac？](#なぜyaac)
- [特徴](#特徴)
- [インストール](#インストール)
- [はじめかた](#はじめかた)
- [コマンド一覧](#コマンド一覧)
- [便利な使い方](#便利な使い方)
- [Connected Apps](#connected-apps)
- [マニフェストで一括デプロイ](#マニフェストで一括デプロイ)
- [メトリクス](#メトリクス)

## なぜYaac？

MuleSoftの公式CLI（anypoint-cli-v4）は出力が装飾されていて、sed/awkなどと組み合わせにくい。Yaacはパイプで繋げやすいシンプルな出力を目指しました。

## 特徴

- **公式CLIの約5倍速い** - ネイティブバイナリ + キャッシュ + 並列API呼び出し
- **HTTP/2対応** - Anypoint Platformへの通信が速い
- **kubectlっぽい出力** - パイプしやすい
- **JSON/YAML/EDN対応** - `-o json`で構造化データ出力
- **組織・環境をキャッシュ** - 毎回APIを叩かない
- **HTTPトレース** - `-V`でリクエスト/レスポンスを確認
- **まとめて操作** - ラベルや正規表現で複数アプリを一括処理

## インストール

### 必要なもの

- Java 21以上
- Clojure CLI: https://clojure.org/guides/install_clojure

### ビルド

```bash
$ git clone https://github.com/myst3m/yaac
$ cd yaac

# Uberjar作成
$ clj -T:build uber

# 実行
$ java --enable-native-access=ALL-UNNAMED -jar target/yaac-0.7.9.jar
```

### ネイティブバイナリ（おすすめ）

起動が15msくらいになります（JVMだと2秒くらい）。

```bash
# GraalVM 25+が必要
$ clj -T:build native-image

# パスの通った場所にコピー
$ cp target/yaac-0.7.9 ~/.local/bin/yaac
```

### Bash補完

```bash
mkdir -p ~/.local/share/bash-completion/completions
cp completions/yaac.bash ~/.local/share/bash-completion/completions/yaac
```

## はじめかた

### 1. 認証情報の登録

Anypoint PlatformでConnected Appを作って、client-idとclient-secretを用意してください。

```bash
$ yaac config credential
app name: my-app
client id: xxxxx
client secret: xxxxx
grant type: 1
 1. client credentials
 2. authorization code
 3. resource owner password
```

`~/.yaac/credentials`に保存されます。パーミッションは600推奨。

### 2. ログイン

```bash
$ yaac login my-app
```

### 3. デフォルトの組織・環境を設定

毎回指定するのは面倒なので、デフォルトを設定できます。

```bash
# 組織T1、環境Productionをデフォルトに
$ yaac config context T1 Production

# デプロイ先も設定
$ yaac config context T1 Production ch2:cloudhub-ap-northeast-1
```

## コマンド一覧

### login

```bash
yaac login <認証情報名>
```

### get - 一覧取得

| コマンド | エイリアス |
|---------|-----------|
| `get organization` | `org` |
| `get environment` | `env` |
| `get application` | `app` |
| `get api-instance` | `api` |
| `get asset` | - |
| `get proxy` | - |
| `get gateway` | `gw` |
| `get runtime-fabric` | `rtf` |
| `get runtime-target` | `rtt` |
| `get private-space` | `ps` |
| `get server` | `serv` |
| `get entitlement` | `ent` |
| `get node-port` | `np` |
| `get contract` | `cont` |
| `get connected-app` | `capp` |
| `get scope` | `scopes` |
| `get user` | - |
| `get team` | - |
| `get idp` | - |
| `get client-provider` | `cp` |
| `get metrics` | - |

```bash
# 組織一覧
$ yaac get org

# T1の環境一覧
$ yaac get env T1

# T1/Productionのアプリ一覧
$ yaac get app T1 Production

# Exchangeでrest-apiを検索
$ yaac get asset -q account types=rest-api
```

### create - 作成

| コマンド | エイリアス |
|---------|-----------|
| `create organization` | `org` |
| `create environment` | `env` |
| `create api-instance` | `api` |
| `create policy` | - |
| `create invite` | - |
| `create connected-app` | `capp` |
| `create client-provider` | `cp` |

```bash
# 子組織を作成（v-cores: 本番:サンドボックス:デザイン）
$ yaac create org T1.1 --parent T1 v-cores=0.1:0.3:0.0

# 環境作成
$ yaac create env T1.1 live type=production

# APIインスタンス作成
$ yaac create api -g T1 -a account-api -v 0.0.1 uri=https://httpbin.org

# Flex Gateway向け
$ yaac create api MuleSoft Production -g MuleSoft -a httpbin-api -v 1.0.0 \
  technology=flexGateway uri=https://httpbin.org target=fg:f1
```

### delete - 削除

`delete`は`del`でも可。

| コマンド | エイリアス |
|---------|-----------|
| `delete organization` | `org` |
| `delete application` | `app` |
| `delete api-instance` | `api` |
| `delete asset` | - |
| `delete contract` | `cont` |
| `delete connected-app` | `capp` |
| `delete client-provider` | `cp` |
| `delete idp-user` | - |
| `delete runtime-fabric` | `rtf` |
| `delete private-space` | `ps` |

```bash
# 組織削除
$ yaac delete org T1

# アセット削除（バージョン指定）
$ yaac delete asset -a hello-api -g T1 -v 0.0.1

# 全バージョン削除
$ yaac delete asset -a hello-api -g T1 -A

# 正規表現でまとめて削除
$ yaac delete app hello.*
```

### deploy - デプロイ

`deploy`は`dep`でも可。

| コマンド | エイリアス |
|---------|-----------|
| `deploy application` | `app` |
| `deploy proxy` | - |
| `deploy manifest` | - |

```bash
# CloudHub 2.0へ
$ yaac deploy app T1 Production my-app -g T1 -a hello-app -v 0.0.1 \
  target=cloudhub-ap-northeast-1

# RTFへ（CPU/メモリ指定）
$ yaac deploy app my-app target=my-cluster -g T1 -a hello-app -v 0.0.1 \
  cpu=500m,1000m mem=700Mi,700Mi

# プロパティ付き（+プレフィックス）
$ yaac deploy app my-app target=my-cluster -g T1 -a hello-app -v 0.0.1 \
  +dbhost=mysql.svc.cluster.local +dbport=3306

# Hybridへ
$ yaac deploy app my-app target=hy:leibniz -g T1 -a hello-app -v 0.0.1
```

### upload - アップロード

```bash
# Muleアプリをアップロード（pomからGAV取得）
$ yaac upload asset app.jar

# GAV指定
$ yaac upload asset app.jar -g T1 -a my-app -v 1.0.0

# RAML
$ yaac upload asset api.raml -g T1 -a my-api -v 1.0.0 --api-version=v1
```

### describe - 詳細

`describe`は`desc`でも可。

| コマンド | エイリアス |
|---------|-----------|
| `describe organization` | `org` |
| `describe environment` | `env` |
| `describe application` | `app` |
| `describe asset` | - |
| `describe api-instance` | `api` |
| `describe connected-app` | `capp` |

### update - 更新

| コマンド | エイリアス |
|---------|-----------|
| `update application` | `app` |
| `update asset` | - |
| `update api-instance` | `api` |
| `update organization` | `org` |
| `update connection` | `conn` |
| `update connected-app` | `capp` |
| `update upstream` | - |
| `update policy` | - |
| `update client-provider` | `cp` |

```bash
# v-coresとレプリカ変更
$ yaac update app hello-world v-cores=0.2 replicas=2

# ラベル追加
$ yaac update asset hello-api labels=db,demo -g T1 -v 0.1.0

# Upstream URI変更
$ yaac update upstream Org Sandbox 20671224 --upstream-uri http://172.23.0.9:8081/

# JWTポリシーのJWKS URL変更
$ yaac update policy Org Sandbox 20671224 jwt-validation-flex \
  --jwks-url http://172.23.0.15:8081/jwks.json
```

### config - 設定

| コマンド | エイリアス |
|---------|-----------|
| `config context` | `ctx` |
| `config credential` | `cred` |
| `config clear-cache` | `cc` |

### logs - ログ

```bash
$ yaac logs app T1 Production hello-app
```

## 便利な使い方

### HTTPトレース

`-V`でリクエスト/レスポンスの流れを確認。

```bash
$ yaac get org -V
===> [0657] GET https://anypoint.mulesoft.com/accounts/api/me
<=== [0657] HTTP/2 200 OK 307ms
NAME      ID                                    PARENT-NAME
MuleSoft  376ce256-2049-4401-a983-d3cb09689ee0  -
```

`-X`だとヘッダやボディも全部見える（並列実行は無効になる）。

### 出力フォーマット

```bash
$ yaac get org -o json   # JSON
$ yaac get org -o yaml   # YAML
$ yaac get org -o edn    # EDN
```

### フィールド選択

```bash
# 特定フィールドだけ
$ yaac get org -F id,name

# フィールド追加
$ yaac get org -F +org-type
```

### キャッシュを使わない

```bash
$ yaac get org -Z
```

## Connected Apps

### スコープ一覧

```bash
$ yaac get scopes
```

### 作成

```bash
# client_credentials
$ yaac create connected-app --name MyApp \
  --grant-types client_credentials \
  --scopes profile \
  --redirect-uris http://localhost

# 組織レベルスコープ付き
$ yaac create connected-app --name MyApp \
  --grant-types client_credentials \
  --redirect-uris http://localhost \
  --scopes profile \
  --org-scopes read:organization,edit:organization
```

### スコープ更新

```bash
# 基本スコープ
$ yaac update connected-app myapp --scopes profile

# 組織レベル
$ yaac update connected-app myapp \
  --org-scopes read:organization,edit:organization \
  --org MyOrg

# 環境レベル（複数環境OK）
$ yaac update connected-app myapp \
  --env-scopes read:applications,create:applications \
  --org MyOrg \
  --env "Production,Sandbox"
```

### スコープ確認

```bash
$ yaac describe connected-app myapp
```

## マニフェストで一括デプロイ

YAMLで複数アプリをまとめてデプロイ。

```bash
# デプロイ
$ yaac deploy manifest deploy.yaml

# ドライラン
$ yaac deploy manifest deploy.yaml --dry-run

# 一部だけ
$ yaac deploy manifest deploy.yaml --only customer-sapi,order-sapi
```

### マニフェスト例

```yaml
organization: T1
environment: Production

targets:
  cloudhub-ap-northeast-1:
    type: ch2
    instance-type: small

  my-rtf-cluster:
    type: rtf
    cpu: [500m, 1000m]
    mem: [1200Mi, 1200Mi]

  leibniz:
    type: hybrid

apps:
  - name: customer-sapi
    asset: T1:customer-sapi:1.0.0
    target: my-rtf-cluster
    properties:
      http.port: 8081
      db.host: oracle.internal

  - name: order-papi
    asset: T1:order-papi:1.0.0
    target: cloudhub-ap-northeast-1
    connects-to:
      - customer-sapi
```

### マニフェスト自動生成

Muleプロジェクトをスキャンして雛形を作れます。

```bash
$ yaac deploy manifest --scan ./system-apis > deploy.yaml
```

## メトリクス

Anypoint Monitoringからメトリクスを取得。

```bash
# 過去1時間のインバウンド
$ yaac get metrics T1 Production --type app-inbound --from 1h

# 特定アプリのレスポンスタイム
$ yaac get metrics T1 Production --type app-inbound-response-time \
  --from 30m --app-id <app-id>

# AMQLで直接クエリ
$ yaac get metrics T1 Production \
  --query 'from inbound.metrics select count() where app.id = "<app-id>"'

# メトリクス構造を確認
$ yaac get metrics T1 Production --describe app-inbound
```

## ベンチマーク

**Anypoint CLI v4**: 6.3秒
```
$ time anypoint-cli-v4 runtime-mgr application list
real	0m6.337s
```

**Yaac**: 0.9秒
```
$ time yaac get app T1 Production
real	0m0.921s
```

## 設定ファイル

| ファイル | 内容 |
|---------|------|
| `~/.yaac/credentials` | 認証情報（JSON） |
| `~/.yaac/session` | アクセストークン |
| `~/.yaac/config` | デフォルト設定（EDN） |
| `~/.yaac/cache` | 組織/環境キャッシュ |

## ライセンス

MIT License
